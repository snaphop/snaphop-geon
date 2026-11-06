package ai.snaphop.geon.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.IndexInfo;
import ai.snaphop.geon.Place;

/// Builds an index file from places.
///
/// Add every place with [#add(Place)], then call [#finish()]. The index is assembled in
/// a temporary directory next to the target and moved into place at the end, so a
/// failed import never leaves a half written index behind.
///
/// ```java
/// try (var writer = IndexWriter.builder(Path.of("andorra.geon")).build()) {
///     var summary = dumpReader.read(input, writer::add);
///     writer.dataTimestamp(summary.dataTimestamp());
///     writer.finish();
/// }
/// ```
///
/// ## Memory
///
/// Places are encoded to a temporary file as they are added, so memory does not grow
/// with their size. What stays in memory:
///
/// * 16 bytes per place for sorting by importance;
/// * one entry per distinct term, string, OSM tag and address set (an address set is
///   the address, context, postcode and country shared by, for example, all houses of
///   one street);
/// * postings, up to the [Builder#memoryBudget(long)]; beyond it they are written to
///   sorted runs on disk and merged at the end.
///
/// Not thread safe.
public final class IndexWriter implements AutoCloseable {

	/// Prefixes up to this many characters get a precomputed list of their most
	/// important places, so a search for `b` does not merge millions of postings.
	static final int PREFIX_LIST_MAX_LENGTH = 3;

	/// How many places a prefix list keeps.
	static final int PREFIX_LIST_CAP = 1024;

	/// Estimated bytes of memory per term in a postings run, beyond its doc IDs.
	private static final int RUN_TERM_OVERHEAD = 96;

	private static final System.Logger logger = System.getLogger(IndexWriter.class.getName());

	private final Path file;

	private final List<String> languages;

	private @Nullable Instant dataTimestamp;

	private final boolean geometries;

	private final long memoryBudget;

	private final Path work;

	private final ByteSink spill;

	private final ByteSink scratch = new ByteSink(1 << 12);

	private final ByteSink stringSink;

	private final DocCodec.StringPool strings;

	private final DocCodec.KeyTable keys = new DocCodec.KeyTable();

	private final Map<String, Integer> tagIds = new LinkedHashMap<>();

	private final ByteSink tags = new ByteSink();

	/// Provisional term IDs in order of first appearance.
	private final Map<String, Integer> termIds = new HashMap<>();

	private final List<String> termStrings = new ArrayList<>();

	private record AddressKey(Map<AddressType, Map<String, String>> parts, Set<String> context,
			@Nullable String postcode, @Nullable String countryCode) {
	}

	private final Map<AddressKey, Integer> addressIds = new HashMap<>();

	private final List<AddressKey> addresses = new ArrayList<>();

	/// Per address set: provisional term ID in the high and mask in the low 32 bits.
	private final List<long[]> addressTerms = new ArrayList<>();

	private long[] sortKeys = new long[1024];

	private long[] spillOffsets = new long[1024];

	private int count;

	private boolean finished;

	private IndexWriter(Builder b) throws IOException {
		this.file = b.file.toAbsolutePath();
		this.languages = List.copyOf(b.languages);
		this.dataTimestamp = b.dataTimestamp;
		this.geometries = b.geometries;
		this.memoryBudget = b.memoryBudget > 0 ? b.memoryBudget : Runtime.getRuntime().maxMemory() / 4;
		Path dir = b.tempDirectory != null ? b.tempDirectory : file.getParent();
		Files.createDirectories(dir);
		this.work = Files.createTempDirectory(dir, ".geon-import-");
		this.spill = ByteSink.toFile(work.resolve("places.spill"));
		this.stringSink = ByteSink.toFile(work.resolve("strings"));
		this.strings = new DocCodec.StringPool(stringSink);
	}

	/// Starts configuring a writer.
	/// @param file the index file to create. It is replaced if it exists.
	/// @return a builder.
	public static Builder builder(Path file) {
		return new Builder(file);
	}

	/// Adds a place.
	/// @param place the place.
	/// @throws IllegalStateException after [#finish()].
	public void add(Place place) {
		if (finished) {
			throw new IllegalStateException("The index writer for " + file + " is already finished.");
		}
		String tagKey = place.osmKey() + '\u0000' + place.osmValue();
		Integer tagId = tagIds.get(tagKey);
		if (tagId == null) {
			tagId = tagIds.size();
			tagIds.put(tagKey, tagId);
			tags.i64(strings.ref(place.osmKey())).i64(strings.ref(place.osmValue()));
		}
		var key = new AddressKey(place.address(), place.context(), place.postcode(), place.countryCode());
		Integer setId = addressIds.get(key);
		if (setId == null) {
			setId = addresses.size();
			addressIds.put(key, setId);
			addresses.add(key);
			addressTerms.add(provisional(DocTerms.address(key.parts(), key.context(), key.postcode(), key.countryCode())));
		}
		if (count == sortKeys.length) {
			sortKeys = Arrays.copyOf(sortKeys, count * 2);
			spillOffsets = Arrays.copyOf(spillOffsets, count * 2);
		}
		// Ascending order of this key is descending importance, then input order.
		float importance = (float) Math.max(0, place.importance());
		sortKeys[count] = ((long) (Integer.MAX_VALUE - Float.floatToIntBits(importance)) << 32) | count;
		spillOffsets[count] = spill.size();
		DocCodec.writeSpill(place, tagId, setId, strings, keys, spill, scratch);
		long[] own = provisional(DocTerms.own(place));
		spill.varint(own.length);
		for (long e : own) {
			spill.varint(e >>> 32).varint((int) e);
		}
		count++;
	}

	private long[] provisional(Map<String, Integer> terms) {
		long[] out = new long[terms.size()];
		int i = 0;
		for (var e : terms.entrySet()) {
			Integer id = termIds.get(e.getKey());
			if (id == null) {
				id = termStrings.size();
				termIds.put(e.getKey(), id);
				termStrings.add(e.getKey());
			}
			out[i++] = ((long) id << 32) | e.getValue();
		}
		return out;
	}

	/// Sets when the source data was current, replacing the builder's value. Dump
	/// readers often learn this only while reading.
	/// @param dataTimestamp the time or `null`.
	public void dataTimestamp(@Nullable Instant dataTimestamp) {
		this.dataTimestamp = dataTimestamp;
	}

	/// Writes the index file.
	/// @return facts about the written index.
	/// @throws IOException if writing fails.
	public IndexInfo finish() throws IOException {
		if (finished) {
			throw new IllegalStateException("The index writer for " + file + " is already finished.");
		}
		finished = true;
		spill.close();
		long start = System.nanoTime();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment places = map(spill.path(), arena);
			Arrays.sort(sortKeys, 0, count);
			int[] rank = rankTerms();
			var sections = new Sections(work);
			var runs = new Runs(rank);
			int[] lonE7 = new int[count];
			int[] latE7 = new int[count];
			// Pass 1, in doc ID order: postings.
			for (int doc = 0; doc < count; doc++) {
				long[] pos = { spillOffsets[(int) sortKeys[doc]] };
				long base = pos[0];
				lonE7[doc] = Seg.i32(places, base);
				latE7[doc] = Seg.i32(places, base + 4);
				boolean hasHouseNumber = (Seg.u8(places, base + 19) & DocCodec.FLAG_HOUSENUMBER) != 0;
				int varLength = Seg.i32(places, base + 20);
				// The var part starts with the address set ID; the own terms follow it.
				pos[0] = base + DocCodec.SPILL_FIXED;
				int setId = Seg.varint32(places, pos);
				pos[0] = base + DocCodec.SPILL_FIXED + varLength;
				int own = Seg.varint32(places, pos);
				for (int i = 0; i < own; i++) {
					int term = Seg.varint32(places, pos);
					int mask = Seg.varint32(places, pos);
					runs.add(term, doc, DocTerms.isPrimary(mask, hasHouseNumber));
				}
				for (long e : addressTerms.get(setId)) {
					runs.add((int) (e >>> 32), doc, DocTerms.isPrimary((int) e, hasHouseNumber));
				}
			}
			int[] finalIds = runs.merge(sections);
			logger.log(Level.DEBUG, "Postings written from {0} runs in {1} ms", runs.files.size(),
					(System.nanoTime() - start) / 1_000_000);
			// Pass 2, in doc ID order: DOCS and DOCVAR with final term IDs.
			try (ByteSink docs = sections.file(Layout.Section.DOCS); ByteSink var = sections.file(Layout.Section.DOCVAR)) {
				long[] entries = new long[64];
				for (int doc = 0; doc < count; doc++) {
					long base = spillOffsets[(int) sortKeys[doc]];
					int varLength = Seg.i32(places, base + 20);
					long[] pos = { base + DocCodec.SPILL_FIXED + varLength };
					int own = Seg.varint32(places, pos);
					if (entries.length < own) {
						entries = new long[own];
					}
					for (int i = 0; i < own; i++) {
						int term = finalIds[Seg.varint32(places, pos)];
						entries[i] = ((long) term << 32) | Seg.varint32(places, pos);
					}
					docs.i32(Seg.i32(places, base))
						.i32(Seg.i32(places, base + 4))
						.i32(Seg.i32(places, base + 8))
						.i32(Seg.i32(places, base + 12))
						.i64(var.size())
						.i16(Seg.i16(places, base + 16))
						.u8(Seg.u8(places, base + 18))
						.u8(Seg.u8(places, base + 19));
					DocCodec.writeTerms(entries, own, var);
					var.bytes(Seg.bytes(places, base + DocCodec.SPILL_FIXED, varLength));
				}
			}
			// Address sets, with final term IDs.
			try (ByteSink index = sections.file(Layout.Section.ADDRESS_INDEX);
					ByteSink address = sections.file(Layout.Section.ADDRESS)) {
				for (int setId = 0; setId < addresses.size(); setId++) {
					index.i64(address.size());
					long[] provisionalTerms = addressTerms.get(setId);
					long[] entries = new long[provisionalTerms.length];
					for (int i = 0; i < entries.length; i++) {
						long e = provisionalTerms[i];
						entries[i] = ((long) finalIds[(int) (e >>> 32)] << 32) | (e & 0xFFFFFFFFL);
					}
					DocCodec.writeTerms(entries, entries.length, address);
					var key = addresses.get(setId);
					DocCodec.encodeAddress(key.parts(), key.context(), key.postcode(), strings, keys, address);
				}
			}
			try (ByteSink tree = sections.file(Layout.Section.RTREE)) {
				PackedRTree.build(lonE7, latE7, tree);
			}
			stringSink.close();
			sections.put(Layout.Section.STRINGS, stringSink);
			sections.put(Layout.Section.TAGS, tags);
			sections.put(Layout.Section.META, meta(runs.termCount));
			sections.writeIndex(file);
			logger.log(Level.DEBUG, "Index {0} written: {1} places, {2} address sets, {3} terms", file, count,
					addresses.size(), runs.termCount);
		}
		finally {
			deleteWork();
		}
		return new IndexInfo(dataTimestamp, languages, geometries, count);
	}

	/// Ranks the provisional term IDs by unsigned UTF-8 order, the dictionary order.
	private int[] rankTerms() {
		int n = termStrings.size();
		byte[][] bytes = new byte[n][];
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			bytes[i] = termStrings.get(i).getBytes(StandardCharsets.UTF_8);
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Arrays.compareUnsigned(bytes[a], bytes[b]));
		int[] rank = new int[n];
		for (int r = 0; r < n; r++) {
			rank[order[r]] = r;
		}
		return rank;
	}

	private ByteSink meta(int termCount) {
		var meta = new ByteSink();
		meta.varint(keys.ids.size());
		keys.ids.keySet().forEach(meta::utf8);
		meta.varint(languages.size());
		languages.forEach(meta::utf8);
		meta.i64(dataTimestamp == null ? Long.MIN_VALUE : dataTimestamp.toEpochMilli());
		meta.u8(geometries ? 1 : 0);
		meta.varint(count);
		meta.varint(termCount);
		return meta;
	}

	/// Postings collected in memory up to the budget, then written as sorted runs and
	/// merged into the final dictionary and postings.
	private final class Runs {

		private final int[] rank;

		private final Map<Integer, IntList[]> current = new HashMap<>();

		private long currentBytes;

		final List<Path> files = new ArrayList<>();

		int termCount;

		Runs(int[] rank) {
			this.rank = rank;
		}

		void add(int term, int doc, boolean primary) {
			IntList[] lists = current.get(term);
			if (lists == null) {
				lists = new IntList[] { new IntList(), new IntList() };
				current.put(term, lists);
				currentBytes += RUN_TERM_OVERHEAD;
			}
			lists[primary ? 0 : 1].add(doc);
			currentBytes += 4;
			if (currentBytes > memoryBudget) {
				flush();
			}
		}

		/// Writes the current run: per term in rank order, the provisional term ID, then
		/// the primary and the secondary doc IDs as counts and deltas.
		private void flush() {
			if (current.isEmpty()) {
				return;
			}
			Path path = work.resolve("run-" + files.size());
			try (ByteSink out = ByteSink.toFile(path)) {
				var terms = current.keySet().toArray(Integer[]::new);
				Arrays.sort(terms, Comparator.comparingInt(t -> rank[t]));
				for (int term : terms) {
					IntList[] lists = current.get(term);
					out.varint(term);
					deltas(out, lists[0]);
					deltas(out, lists[1]);
				}
			}
			catch (IOException e) {
				throw new UncheckedIOException("Cannot write postings run " + path, e);
			}
			files.add(path);
			current.clear();
			currentBytes = 0;
		}

		private static void deltas(ByteSink out, IntList docs) {
			out.varint(docs.size());
			int previous = 0;
			for (int i = 0; i < docs.size(); i++) {
				out.varint(docs.get(i) - previous);
				previous = docs.get(i);
			}
		}

		/// A position in one run file.
		private final class Cursor {

			final int run;

			final MemorySegment segment;

			final long[] pos = { 0 };

			int term = -1;

			Cursor(int run, MemorySegment segment) {
				this.run = run;
				this.segment = segment;
			}

			boolean advance() {
				if (pos[0] >= segment.byteSize()) {
					term = -1;
					return false;
				}
				term = Seg.varint32(segment, pos);
				return true;
			}

			void readDocs(IntList out) {
				int n = Seg.varint32(segment, pos);
				int doc = 0;
				for (int i = 0; i < n; i++) {
					doc += Seg.varint32(segment, pos);
					out.add(doc);
				}
			}

		}

		/// Merges the runs into the dictionary and postings sections.
		/// @return the final term ID of each provisional term ID.
		int[] merge(Sections sections) throws IOException {
			flush();
			int[] finalIds = new int[rank.length];
			ByteSink categories = ByteSink.toFile(work.resolve("postings-categories"));
			ByteSink prefixes = ByteSink.toFile(work.resolve("postings-prefixes"));
			ByteSink words = ByteSink.toFile(work.resolve("postings-words"));
			var categoryTerms = new ArrayList<Integer>();
			var wordTerms = new ArrayList<Integer>();
			var categoryEntries = new LongIntInt();
			var wordEntries = new LongIntInt();
			var prefixLists = new PrefixLists(prefixes);
			try (Arena arena = Arena.ofConfined()) {
				var queue = new PriorityQueue<Cursor>(
						Comparator.comparingInt((Cursor c) -> rank[c.term]).thenComparingInt(c -> c.run));
				for (int i = 0; i < files.size(); i++) {
					var cursor = new Cursor(i, map(files.get(i), arena));
					if (cursor.advance()) {
						queue.add(cursor);
					}
				}
				var primary = new IntList();
				var secondary = new IntList();
				while (!queue.isEmpty()) {
					int term = queue.peek().term;
					primary.clear();
					secondary.clear();
					// Runs hold ascending doc ranges, so concatenating in run order keeps
					// each list ascending.
					var parts = new ArrayList<Cursor>();
					while (!queue.isEmpty() && queue.peek().term == term) {
						parts.add(queue.poll());
					}
					parts.sort(Comparator.comparingInt(c -> c.run));
					var secondaries = new ArrayList<IntList>();
					for (Cursor c : parts) {
						c.readDocs(primary);
						var s = new IntList();
						c.readDocs(s);
						secondaries.add(s);
						if (c.advance()) {
							queue.add(c);
						}
					}
					secondaries.forEach(secondary::addAll);
					String text = termStrings.get(term);
					boolean category = text.startsWith(Field.CATEGORY_PREFIX);
					ByteSink out = category ? categories : words;
					(category ? categoryEntries : wordEntries).add(out.size(), primary.size(),
							primary.size() + secondary.size());
					writeDocs(out, primary);
					writeDocs(out, secondary);
					(category ? categoryTerms : wordTerms).add(term);
					if (!category) {
						prefixLists.add(text, primary);
					}
				}
				prefixLists.finish();
			}
			finally {
				categories.close();
				prefixes.close();
				words.close();
			}
			int c = categoryTerms.size();
			int p = prefixLists.terms.size();
			for (int i = 0; i < c; i++) {
				finalIds[categoryTerms.get(i)] = i;
			}
			for (int i = 0; i < wordTerms.size(); i++) {
				finalIds[wordTerms.get(i)] = c + p + i;
			}
			termCount = c + p + wordTerms.size();
			writeDictionary(sections, categoryTerms, prefixLists.terms, wordTerms);
			try (ByteSink index = sections.file(Layout.Section.POSTING_INDEX)) {
				// Index entries: offset, primary count, total count; in final term ID order.
				long categoryBytes = categories.size();
				long prefixBytes = prefixes.size();
				categoryEntries.writeTo(index, 0);
				prefixLists.entries.writeTo(index, categoryBytes);
				wordEntries.writeTo(index, categoryBytes + prefixBytes);
			}
			sections.concat(Layout.Section.POSTINGS, categories.path(), prefixes.path(), words.path());
			return finalIds;
		}

		private void writeDictionary(Sections sections, List<Integer> categoryTerms, List<String> prefixTerms,
				List<Integer> wordTerms) throws IOException {
			try (ByteSink dict = sections.file(Layout.Section.TERMS);
					ByteSink blocks = sections.file(Layout.Section.TERM_BLOCKS)) {
				var writer = new DictionaryWriter(dict, blocks);
				categoryTerms.forEach(t -> writer.add(termStrings.get(t)));
				prefixTerms.forEach(t -> writer.add(Field.PREFIX_LIST_PREFIX + t));
				wordTerms.forEach(t -> writer.add(termStrings.get(t)));
			}
		}

	}

	private static void writeDocs(ByteSink out, IntList docs) {
		int previous = 0;
		for (int i = 0; i < docs.size(); i++) {
			out.varint(docs.get(i) - previous);
			previous = docs.get(i);
		}
	}

	/// Front codes sorted terms into blocks of [Layout#TERM_BLOCK].
	private static final class DictionaryWriter {

		private final ByteSink dict;

		private final ByteSink blocks;

		private byte[] previous = new byte[0];

		private int count;

		DictionaryWriter(ByteSink dict, ByteSink blocks) {
			this.dict = dict;
			this.blocks = blocks;
		}

		void add(String term) {
			byte[] bytes = term.getBytes(StandardCharsets.UTF_8);
			if (count % Layout.TERM_BLOCK == 0) {
				blocks.i64(dict.size());
				dict.varint(bytes.length).bytes(bytes);
			}
			else {
				int shared = 0;
				int n = Math.min(previous.length, bytes.length);
				while (shared < n && previous[shared] == bytes[shared]) {
					shared++;
				}
				dict.varint(shared).varint(bytes.length - shared).bytes(bytes, shared, bytes.length - shared);
			}
			previous = bytes;
			count++;
		}

	}

	/// Builds the capped lists of the most important places for each short prefix.
	///
	/// Terms arrive in dictionary order, so all terms sharing a prefix are adjacent and
	/// only one list per prefix length is open at a time.
	private static final class PrefixLists {

		final List<String> terms = new ArrayList<>();

		final LongIntInt entries = new LongIntInt();

		private final ByteSink out;

		private final String[] open = new String[PREFIX_LIST_MAX_LENGTH + 1];

		private final IntList[] docs = new IntList[PREFIX_LIST_MAX_LENGTH + 1];

		/// Finished lists, kept until all are known so they can be written in order.
		private final List<Map.Entry<String, int[]>> done = new ArrayList<>();

		PrefixLists(ByteSink out) {
			this.out = out;
			for (int i = 0; i < docs.length; i++) {
				docs[i] = new IntList();
			}
		}

		void add(String term, IntList primary) {
			int codePoints = term.codePointCount(0, term.length());
			for (int len = 1; len <= PREFIX_LIST_MAX_LENGTH; len++) {
				String prefix = len <= codePoints ? term.substring(0, term.offsetByCodePoints(0, len)) : null;
				if (prefix == null || !prefix.equals(open[len])) {
					close(len);
					open[len] = prefix;
				}
				if (prefix != null && primary.size() > 0) {
					IntList list = docs[len];
					for (int i = 0; i < primary.size() && i < PREFIX_LIST_CAP; i++) {
						list.add(primary.get(i));
					}
					if (list.size() > 4 * PREFIX_LIST_CAP) {
						list.sortUniqueTruncate(PREFIX_LIST_CAP);
					}
				}
			}
		}

		private void close(int len) {
			String prefix = open[len];
			IntList list = docs[len];
			if (prefix != null && list.size() > 0) {
				list.sortUniqueTruncate(PREFIX_LIST_CAP);
				done.add(Map.entry(prefix, list.toArray()));
			}
			list.clear();
			open[len] = null;
		}

		void finish() {
			for (int len = 1; len <= PREFIX_LIST_MAX_LENGTH; len++) {
				close(len);
			}
			// Prefixes of different lengths closed interleaved; the dictionary needs them sorted.
			done.sort((a, b) -> Arrays.compareUnsigned(a.getKey().getBytes(StandardCharsets.UTF_8),
					b.getKey().getBytes(StandardCharsets.UTF_8)));
			for (var e : done) {
				terms.add(e.getKey());
				int[] list = e.getValue();
				entries.add(out.size(), list.length, list.length);
				int previous = 0;
				for (int doc : list) {
					out.varint(doc - previous);
					previous = doc;
				}
			}
			done.clear();
		}

	}

	/// A growable list of posting index entries.
	private static final class LongIntInt {

		private long[] offsets = new long[1024];

		private long[] counts = new long[1024];

		private int size;

		void add(long offset, int primary, int total) {
			if (size == offsets.length) {
				offsets = Arrays.copyOf(offsets, size * 2);
				counts = Arrays.copyOf(counts, size * 2);
			}
			offsets[size] = offset;
			counts[size++] = ((long) total << 32) | primary;
		}

		void writeTo(ByteSink index, long base) {
			for (int i = 0; i < size; i++) {
				index.i64(base + offsets[i]).i32((int) counts[i]).i32((int) (counts[i] >>> 32));
			}
		}

	}

	/// The sections of the index being written, each in memory or in a work file.
	private static final class Sections {

		private final Path work;

		private final Map<Layout.Section, List<Object>> parts = new HashMap<>();

		Sections(Path work) {
			this.work = work;
		}

		ByteSink file(Layout.Section section) throws IOException {
			var sink = ByteSink.toFile(work.resolve("section-" + section.name().toLowerCase(java.util.Locale.ROOT)));
			parts.put(section, List.of(sink));
			return sink;
		}

		void put(Layout.Section section, ByteSink sink) {
			parts.put(section, List.of(sink));
		}

		void concat(Layout.Section section, Path... files) {
			parts.put(section, List.of((Object[]) files));
		}

		private static long size(Object part) throws IOException {
			return part instanceof ByteSink s ? s.size() : Files.size((Path) part);
		}

		void writeIndex(Path target) throws IOException {
			var all = Layout.Section.values();
			Path tmp = Files.createTempFile(work, "index", ".tmp");
			try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
				int headerSize = Layout.HEADER_FIXED + Layout.SECTION_ENTRY * all.length;
				var header = new ByteSink(headerSize);
				header.bytes(Layout.MAGIC).i32(Layout.VERSION).i32(all.length);
				long offset = align(headerSize);
				for (var section : all) {
					long length = 0;
					for (Object part : parts.getOrDefault(section, List.of())) {
						length += size(part);
					}
					header.i64(offset).i64(length);
					offset = align(offset + length);
				}
				header.writeTo(channel);
				for (var section : all) {
					pad(channel);
					for (Object part : parts.getOrDefault(section, List.of())) {
						if (part instanceof ByteSink s && !isFile(s)) {
							s.writeTo(channel);
						}
						else {
							Path p = part instanceof ByteSink s ? s.path() : (Path) part;
							try (var in = FileChannel.open(p, StandardOpenOption.READ)) {
								long size = in.size();
								long done = 0;
								while (done < size) {
									done += in.transferTo(done, size - done, channel);
								}
							}
						}
					}
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}

		private static boolean isFile(ByteSink s) {
			try {
				s.path();
				return true;
			}
			catch (IllegalStateException e) {
				return false;
			}
		}

		private static long align(long offset) {
			return (offset + 7) & ~7L;
		}

		private static void pad(FileChannel channel) throws IOException {
			long position = channel.position();
			long aligned = align(position);
			if (aligned > position) {
				channel.write(java.nio.ByteBuffer.allocate((int) (aligned - position)));
			}
		}

	}

	private static MemorySegment map(Path path, Arena arena) throws IOException {
		try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
			return channel.size() == 0 ? MemorySegment.ofArray(new byte[0])
					: channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
		}
	}

	private void deleteWork() {
		try (var files = Files.list(work)) {
			for (Path p : files.toList()) {
				Files.deleteIfExists(p);
			}
			Files.deleteIfExists(work);
		}
		catch (IOException e) {
			logger.log(Level.WARNING, "Could not delete the import work directory " + work, e);
		}
	}

	/// Discards everything if [#finish()] was not called.
	@Override
	public void close() {
		if (!finished) {
			finished = true;
			spill.close();
			stringSink.close();
			deleteWork();
		}
	}

	/// Configures an [IndexWriter].
	public static final class Builder {

		private final Path file;

		private List<String> languages = List.of();

		private @Nullable Instant dataTimestamp;

		private boolean geometries;

		private long memoryBudget;

		private @Nullable Path tempDirectory;

		private Builder(Path file) {
			this.file = file;
		}

		/// The languages the places were imported with, recorded for [IndexInfo#languages()].
		/// @param languages language codes.
		/// @return this.
		public Builder languages(List<String> languages) {
			this.languages = languages;
			return this;
		}

		/// When the source data was current, recorded for [IndexInfo#dataTimestamp()].
		/// @param dataTimestamp the time or `null`.
		/// @return this.
		public Builder dataTimestamp(@Nullable Instant dataTimestamp) {
			this.dataTimestamp = dataTimestamp;
			return this;
		}

		/// Whether places carry geometries, recorded for [IndexInfo#hasGeometries()].
		/// @param geometries `true` if they do.
		/// @return this.
		public Builder geometries(boolean geometries) {
			this.geometries = geometries;
			return this;
		}

		/// How many bytes of postings to collect in memory before writing a sorted run to
		/// disk. More memory means fewer runs and a faster merge. Defaults to a quarter of
		/// the maximum heap. See the class documentation for what else uses memory.
		/// @param bytes the budget; 0 or less uses the default.
		/// @return this.
		public Builder memoryBudget(long bytes) {
			this.memoryBudget = bytes;
			return this;
		}

		/// Where to put temporary files. Defaults to the index file's directory. They can
		/// take more room than the finished index.
		/// @param dir the directory.
		/// @return this.
		public Builder tempDirectory(Path dir) {
			this.tempDirectory = dir;
			return this;
		}

		/// Creates the writer and its temporary files.
		/// @return the writer.
		/// @throws IOException if the temporary files cannot be created.
		public IndexWriter build() throws IOException {
			return new IndexWriter(this);
		}

	}

}
