package ai.snaphop.geon.index;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.IndexInfo;
import ai.snaphop.geon.Place;

/// Builds an index file from places.
///
/// Add every place with [#add(Place)], then call [#finish()]. The file is written to a
/// temporary name next to the target and moved into place at the end, so a failed
/// import never leaves a half written index behind.
///
/// ```java
/// try (var writer = IndexWriter.builder(Path.of("andorra.geon")).build()) {
///     dumpReader.read(input, writer::add);
///     writer.finish();
/// }
/// ```
///
/// This first version keeps all places in memory until [#finish()]. That is fine
/// for country extracts; planet imports will need the external sort described in
/// the design document.
///
/// Not thread safe.
public final class IndexWriter implements AutoCloseable {

	/// Prefixes up to this many characters get a precomputed list of their most
	/// important places, so a search for `b` does not merge millions of postings.
	static final int PREFIX_LIST_MAX_LENGTH = 3;

	/// How many places a prefix list keeps.
	static final int PREFIX_LIST_CAP = 1024;

	private final Path file;

	private final List<String> languages;

	private final @Nullable Instant dataTimestamp;

	private final boolean geometries;

	private final List<Place> places = new ArrayList<>();

	private boolean finished;

	private IndexWriter(Builder b) {
		this.file = b.file;
		this.languages = List.copyOf(b.languages);
		this.dataTimestamp = b.dataTimestamp;
		this.geometries = b.geometries;
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
		places.add(place);
	}

	/// Writes the index file.
	/// @return facts about the written index.
	/// @throws IOException if writing fails.
	public IndexInfo finish() throws IOException {
		if (finished) {
			throw new IllegalStateException("The index writer for " + file + " is already finished.");
		}
		finished = true;
		Place[] docs = places.toArray(Place[]::new);
		places.clear();
		// Doc IDs follow descending importance, so postings are in importance order.
		// The sort is stable, which keeps the dump order for equal importance.
		Arrays.sort(docs, (a, b) -> Double.compare(b.importance(), a.importance()));

		var terms = collectTerms(docs);
		addPrefixLists(terms);
		byte[][] sortedTerms = sortTerms(terms.keySet());
		var termIds = new HashMap<String, Integer>(sortedTerms.length * 2);
		for (int i = 0; i < sortedTerms.length; i++) {
			termIds.put(new String(sortedTerms[i], StandardCharsets.UTF_8), i);
		}

		var sections = new ByteSink[Layout.Section.values().length];
		writeTerms(sortedTerms, terms, sections);
		writeDocs(docs, termIds, sections);
		sections[Layout.Section.META.ordinal()] = meta(docs.length, sortedTerms.length,
				sections[Layout.Section.META.ordinal()]);
		write(sections);
		return new IndexInfo(dataTimestamp, languages, geometries, docs.length);
	}

	/// The doc IDs of one term, split into primary and secondary lists.
	private static final class Postings {

		final IntList primary = new IntList();

		final IntList secondary = new IntList();

	}

	private static Map<String, Postings> collectTerms(Place[] docs) {
		var terms = new HashMap<String, Postings>();
		for (int doc = 0; doc < docs.length; doc++) {
			boolean hasHouseNumber = docs[doc].houseNumber() != null;
			for (var e : DocTerms.of(docs[doc]).entrySet()) {
				var postings = terms.computeIfAbsent(e.getKey(), k -> new Postings());
				(DocTerms.isPrimary(e.getValue(), hasHouseNumber) ? postings.primary : postings.secondary).add(doc);
			}
		}
		return terms;
	}

	private static void addPrefixLists(Map<String, Postings> terms) {
		var prefixes = new HashMap<String, IntList>();
		for (var e : terms.entrySet()) {
			String term = e.getKey();
			IntList primary = e.getValue().primary;
			if (primary.size() == 0 || term.startsWith(Field.CATEGORY_PREFIX)) {
				continue;
			}
			int cps = term.codePointCount(0, term.length());
			for (int len = 1; len <= Math.min(PREFIX_LIST_MAX_LENGTH, cps); len++) {
				String prefix = term.substring(0, term.offsetByCodePoints(0, len));
				IntList list = prefixes.computeIfAbsent(prefix, k -> new IntList());
				for (int i = 0; i < primary.size() && i < PREFIX_LIST_CAP; i++) {
					list.add(primary.get(i));
				}
				if (list.size() > 4 * PREFIX_LIST_CAP) {
					list.sortUniqueTruncate(PREFIX_LIST_CAP);
				}
			}
		}
		prefixes.forEach((prefix, list) -> {
			list.sortUniqueTruncate(PREFIX_LIST_CAP);
			var postings = new Postings();
			postings.primary.addAll(list);
			terms.put(Field.PREFIX_LIST_PREFIX + prefix, postings);
		});
	}

	private static byte[][] sortTerms(java.util.Set<String> terms) {
		byte[][] sorted = new byte[terms.size()][];
		int i = 0;
		for (String t : terms) {
			sorted[i++] = t.getBytes(StandardCharsets.UTF_8);
		}
		Arrays.sort(sorted, Arrays::compareUnsigned);
		return sorted;
	}

	private static void writeTerms(byte[][] sortedTerms, Map<String, Postings> terms, ByteSink[] sections) {
		var dict = new ByteSink(sortedTerms.length * 8);
		var blocks = new ByteSink(sortedTerms.length / Layout.TERM_BLOCK * 8 + 8);
		var index = new ByteSink(sortedTerms.length * Layout.POSTING_ENTRY);
		var postings = new ByteSink(sortedTerms.length * 8);
		byte[] previous = null;
		for (int id = 0; id < sortedTerms.length; id++) {
			byte[] term = sortedTerms[id];
			if (id % Layout.TERM_BLOCK == 0) {
				blocks.i64(dict.size());
				dict.varint(term.length).bytes(term);
			}
			else {
				int shared = sharedPrefix(previous, term);
				dict.varint(shared).varint(term.length - shared).bytes(term, shared, term.length - shared);
			}
			previous = term;
			Postings p = terms.get(new String(term, StandardCharsets.UTF_8));
			index.i64(postings.size()).i32(p.primary.size()).i32(p.primary.size() + p.secondary.size());
			deltas(postings, p.primary);
			deltas(postings, p.secondary);
		}
		sections[Layout.Section.TERMS.ordinal()] = dict;
		sections[Layout.Section.TERM_BLOCKS.ordinal()] = blocks;
		sections[Layout.Section.POSTING_INDEX.ordinal()] = index;
		sections[Layout.Section.POSTINGS.ordinal()] = postings;
	}

	private static int sharedPrefix(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		int i = 0;
		while (i < n && a[i] == b[i]) {
			i++;
		}
		return i;
	}

	private static void deltas(ByteSink out, IntList docs) {
		int previous = 0;
		for (int i = 0; i < docs.size(); i++) {
			out.varint(docs.get(i) - previous);
			previous = docs.get(i);
		}
	}

	private void writeDocs(Place[] docs, Map<String, Integer> termIds, ByteSink[] sections) {
		var strings = new DocCodec.StringPool();
		var keys = new DocCodec.KeyTable();
		var tagIds = new LinkedHashMap<String, Integer>();
		var tagSink = new ByteSink();
		var docSink = new ByteSink(docs.length * Layout.DOC_RECORD);
		var varSink = new ByteSink(docs.length * 64);
		var forward = new ByteSink(docs.length * 32);
		int[] lon = new int[docs.length];
		int[] lat = new int[docs.length];
		for (int doc = 0; doc < docs.length; doc++) {
			Place p = docs[doc];
			String tagKey = p.osmKey() + '\u0000' + p.osmValue();
			Integer tagId = tagIds.get(tagKey);
			if (tagId == null) {
				tagId = tagIds.size();
				tagIds.put(tagKey, tagId);
				tagSink.i64(strings.ref(p.osmKey())).i64(strings.ref(p.osmValue()));
			}
			long forwardOffset = forward.size();
			var docTerms = DocTerms.of(p);
			long[] entries = new long[docTerms.size()];
			int i = 0;
			for (var e : docTerms.entrySet()) {
				entries[i++] = ((long) termIds.get(e.getKey()) << 32) | e.getValue();
			}
			Arrays.sort(entries);
			forward.varint(entries.length);
			int previous = 0;
			for (long entry : entries) {
				int termId = (int) (entry >>> 32);
				forward.varint(termId - previous).varint((int) entry);
				previous = termId;
			}
			DocCodec.encode(p, tagId, forwardOffset, strings, keys, docSink, varSink);
			lon[doc] = Layout.toE7(p.centroid().lon());
			lat[doc] = Layout.toE7(p.centroid().lat());
		}
		var meta = new ByteSink();
		meta.varint(keys.ids.size());
		keys.ids.keySet().forEach(meta::utf8);
		sections[Layout.Section.META.ordinal()] = meta;
		sections[Layout.Section.STRINGS.ordinal()] = strings.sink;
		sections[Layout.Section.TAGS.ordinal()] = tagSink;
		sections[Layout.Section.DOCS.ordinal()] = docSink;
		sections[Layout.Section.DOCVAR.ordinal()] = varSink;
		sections[Layout.Section.FORWARD.ordinal()] = forward;
		sections[Layout.Section.RTREE.ordinal()] = PackedRTree.build(lon, lat);
	}

	/// Appends the rest of the metadata after the name keys.
	private ByteSink meta(int placeCount, int termCount, ByteSink meta) {
		meta.varint(languages.size());
		languages.forEach(meta::utf8);
		meta.i64(dataTimestamp == null ? Long.MIN_VALUE : dataTimestamp.toEpochMilli());
		meta.u8(geometries ? 1 : 0);
		meta.varint(placeCount);
		meta.varint(termCount);
		return meta;
	}

	private void write(ByteSink[] sections) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
		try {
			try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				int headerSize = Layout.HEADER_FIXED + Layout.SECTION_ENTRY * sections.length;
				var header = new ByteSink(headerSize);
				header.bytes(Layout.MAGIC).i32(Layout.VERSION).i32(sections.length);
				long offset = align(headerSize);
				for (ByteSink s : sections) {
					header.i64(offset).i64(s.size());
					offset = align(offset + s.size());
				}
				header.writeTo(channel);
				long written = headerSize;
				for (ByteSink s : sections) {
					written = pad(channel, written);
					s.writeTo(channel);
					written += s.size();
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static long align(long offset) {
		return (offset + 7) & ~7L;
	}

	private static long pad(FileChannel channel, long written) throws IOException {
		long aligned = align(written);
		if (aligned > written) {
			new ByteSink(8).bytes(new byte[(int) (aligned - written)]).writeTo(channel);
		}
		return aligned;
	}

	/// Discards the places if [#finish()] was not called.
	@Override
	public void close() {
		places.clear();
	}

	/// Configures an [IndexWriter].
	public static final class Builder {

		private final Path file;

		private List<String> languages = List.of();

		private @Nullable Instant dataTimestamp;

		private boolean geometries;

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

		/// Creates the writer.
		/// @return the writer.
		public IndexWriter build() {
			return new IndexWriter(this);
		}

	}

}
