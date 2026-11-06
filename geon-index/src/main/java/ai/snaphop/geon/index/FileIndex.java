package ai.snaphop.geon.index;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.snaphop.geon.GeonIndex;
import ai.snaphop.geon.Hit;
import ai.snaphop.geon.IndexInfo;
import ai.snaphop.geon.ReverseQuery;
import ai.snaphop.geon.SearchQuery;
import ai.snaphop.geon.StructuredQuery;
import ai.snaphop.geon.Synonyms;

/// A [GeonIndex] backed by a memory mapped file written by [IndexWriter].
///
/// Opening maps the file and reads a few kilobytes of metadata; nothing else is
/// loaded. The operating system pages the rest in as queries touch it. The index is
/// thread safe; [#close()] unmaps the file, after which queries fail.
public final class FileIndex implements GeonIndex {

	private final Path file;

	private final Arena arena;

	private final IndexInfo info;

	private final Searcher searcher;

	private FileIndex(Path file, Arena arena, IndexInfo info, Searcher searcher) {
		this.file = file;
		this.arena = arena;
		this.info = info;
		this.searcher = searcher;
	}

	/// Opens an index file.
	/// @param file the file written by [IndexWriter].
	/// @return the open index.
	/// @throws IOException if the file cannot be read or is not a Geon index of a
	///   supported version.
	public static FileIndex open(Path file) throws IOException {
		return open(file, Synonyms.NONE);
	}

	/// Opens an index file with query time synonyms and classification terms.
	/// @param file the file written by [IndexWriter].
	/// @param synonyms the vocabulary to apply to queries.
	/// @return the open index.
	/// @throws IOException if the file cannot be read or is not a Geon index of a
	///   supported version.
	public static FileIndex open(Path file, Synonyms synonyms) throws IOException {
		Arena arena = Arena.ofShared();
		try {
			MemorySegment all;
			try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
				all = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
			}
			if (all.byteSize() < Layout.HEADER_FIXED
					|| !Arrays.equals(Seg.bytes(all, 0, Layout.MAGIC.length), Layout.MAGIC)) {
				throw new IOException("Not a Geon index file: " + file);
			}
			int version = Seg.i32(all, 8);
			if (version != Layout.VERSION) {
				throw new IOException("Index file " + file + " has format version " + version
						+ " but this build reads version " + Layout.VERSION + ". Re-import the data.");
			}
			int sectionCount = Seg.i32(all, 12);
			var sections = new MemorySegment[Layout.Section.values().length];
			if (sectionCount != sections.length) {
				throw new IOException("Index file " + file + " has " + sectionCount + " sections, expected "
						+ sections.length + ". The file is damaged.");
			}
			for (int i = 0; i < sections.length; i++) {
				long base = Layout.HEADER_FIXED + (long) Layout.SECTION_ENTRY * i;
				sections[i] = all.asSlice(Seg.i64(all, base), Seg.i64(all, base + 8));
			}
			return open(file, arena, sections, synonyms);
		}
		catch (IOException | RuntimeException e) {
			arena.close();
			throw e;
		}
	}

	private static FileIndex open(Path file, Arena arena, MemorySegment[] sections, Synonyms synonyms) {
		MemorySegment meta = sections[Layout.Section.META.ordinal()];
		long[] pos = { 0 };
		int keyCount = Seg.varint32(meta, pos);
		var keys = new ArrayList<String>(keyCount);
		for (int i = 0; i < keyCount; i++) {
			keys.add(Seg.utf8(meta, pos));
		}
		int languageCount = Seg.varint32(meta, pos);
		var languages = new ArrayList<String>(languageCount);
		for (int i = 0; i < languageCount; i++) {
			languages.add(Seg.utf8(meta, pos));
		}
		long timestamp = Seg.i64(meta, pos[0]);
		pos[0] += 8;
		boolean geometries = Seg.u8(meta, pos[0]++) == 1;
		long placeCount = Seg.varint(meta, pos);
		int termCount = Seg.varint32(meta, pos);

		MemorySegment strings = sections[Layout.Section.STRINGS.ordinal()];
		MemorySegment tagSection = sections[Layout.Section.TAGS.ordinal()];
		String[][] tags = new String[(int) (tagSection.byteSize() / 16)][];
		for (int i = 0; i < tags.length; i++) {
			tags[i] = new String[] { string(strings, Seg.i64(tagSection, 16L * i)),
					string(strings, Seg.i64(tagSection, 16L * i + 8)) };
		}
		var docs = new DocCodec.Reader(strings, sections[Layout.Section.DOCS.ordinal()],
				sections[Layout.Section.DOCVAR.ordinal()], sections[Layout.Section.ADDRESS_INDEX.ordinal()],
				sections[Layout.Section.ADDRESS.ordinal()], keys, tags);
		var terms = new Terms(sections[Layout.Section.TERMS.ordinal()],
				sections[Layout.Section.TERM_BLOCKS.ordinal()], sections[Layout.Section.POSTING_INDEX.ordinal()],
				sections[Layout.Section.POSTINGS.ordinal()], termCount);
		var tree = new PackedRTree.Reader(sections[Layout.Section.RTREE.ordinal()], docs);
		var info = new IndexInfo(timestamp == Long.MIN_VALUE ? null : Instant.ofEpochMilli(timestamp), languages,
				geometries, placeCount);
		var searcher = new Searcher(docs, terms, new Forward(docs), tree, synonyms);
		return new FileIndex(file, arena, info, searcher);
	}

	private static String string(MemorySegment strings, long ref) {
		long[] p = { ref };
		return Seg.utf8(strings, p);
	}

	@Override
	public IndexInfo info() {
		return info;
	}

	@Override
	public List<Hit> search(SearchQuery query) {
		return searcher.search(query);
	}

	@Override
	public List<Hit> search(StructuredQuery query) {
		return searcher.search(query);
	}

	@Override
	public List<Hit> reverse(ReverseQuery query) {
		return searcher.reverse(query);
	}

	@Override
	public void close() {
		arena.close();
	}

	@Override
	public String toString() {
		return "FileIndex[" + file + ", " + info.placeCount() + " places]";
	}

}
