package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Reads the term dictionary and postings. Thread safe.
///
/// Terms are compared as unsigned UTF-8 bytes, which is code point order, so all
/// terms starting with a prefix form one contiguous ID range.
final class Terms {

	private final MemorySegment dict;

	private final MemorySegment blocks;

	private final MemorySegment index;

	private final MemorySegment postings;

	private final int count;

	private final int blockCount;

	Terms(MemorySegment dict, MemorySegment blocks, MemorySegment index, MemorySegment postings, int count) {
		this.dict = dict;
		this.blocks = blocks;
		this.index = index;
		this.postings = postings;
		this.count = count;
		this.blockCount = (int) (blocks.byteSize() / 8);
	}

	int count() {
		return count;
	}

	static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private byte[] blockFirst(int block) {
		long[] pos = { Seg.i64(blocks, 8L * block) };
		int len = Seg.varint32(dict, pos);
		return Seg.bytes(dict, pos[0], len);
	}

	/// Calls the visitor for each term from `from` (inclusive) on, until it returns
	/// `false` or the dictionary ends.
	void scan(int from, TermVisitor visitor) {
		if (from >= count) {
			return;
		}
		int block = from / Layout.TERM_BLOCK;
		long[] pos = { Seg.i64(blocks, 8L * block) };
		byte[] term = new byte[64];
		int termLen = 0;
		for (int id = block * Layout.TERM_BLOCK; id < count; id++) {
			if (id % Layout.TERM_BLOCK == 0) {
				pos[0] = Seg.i64(blocks, 8L * (id / Layout.TERM_BLOCK));
				termLen = Seg.varint32(dict, pos);
				term = ensure(term, termLen);
				MemorySegment.copy(dict, ValueLayout.JAVA_BYTE, pos[0], term, 0, termLen);
				pos[0] += termLen;
			}
			else {
				int shared = Seg.varint32(dict, pos);
				int suffix = Seg.varint32(dict, pos);
				termLen = shared + suffix;
				term = ensure(term, termLen);
				MemorySegment.copy(dict, ValueLayout.JAVA_BYTE, pos[0], term, shared, suffix);
				pos[0] += suffix;
			}
			if (id >= from && !visitor.visit(id, term, termLen)) {
				return;
			}
		}
	}

	private static byte[] ensure(byte[] b, int len) {
		return b.length >= len ? b : Arrays.copyOf(b, Math.max(len, b.length * 2));
	}

	/// The ID of the first term that is not less than `key`, or [#count()] if none.
	int lowerBound(byte[] key) {
		if (count == 0) {
			return 0;
		}
		// Find the last block whose first term is <= key.
		int lo = 0;
		int hi = blockCount - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (Arrays.compareUnsigned(blockFirst(mid), key) <= 0) {
				lo = mid;
			}
			else {
				hi = mid - 1;
			}
		}
		int[] found = { count };
		scan(lo * Layout.TERM_BLOCK, (id, term, len) -> {
			if (Arrays.compareUnsigned(term, 0, len, key, 0, key.length) >= 0) {
				found[0] = id;
				return false;
			}
			return true;
		});
		return found[0];
	}

	/// The ID of a term, or `-1` if absent.
	int find(byte[] key) {
		int id = lowerBound(key);
		if (id >= count) {
			return -1;
		}
		boolean[] equal = { false };
		scan(id, (i, term, len) -> {
			equal[0] = Arrays.equals(term, 0, len, key, 0, key.length);
			return false;
		});
		return equal[0] ? id : -1;
	}

	/// The IDs of the terms starting with `prefix`, as `[from, to)`.
	int[] prefixRange(byte[] prefix) {
		int from = lowerBound(prefix);
		int[] to = { from };
		scan(from, (id, term, len) -> {
			if (len >= prefix.length && Arrays.equals(term, 0, prefix.length, prefix, 0, prefix.length)) {
				to[0] = id + 1;
				return true;
			}
			return false;
		});
		return new int[] { from, to[0] };
	}

	/// How many docs have the term as a primary term.
	int primaryCount(int id) {
		return Seg.i32(index, (long) id * Layout.POSTING_ENTRY + 8);
	}

	/// How many docs have the term at all.
	int totalCount(int id) {
		return Seg.i32(index, (long) id * Layout.POSTING_ENTRY + 12);
	}

	/// Adds the doc IDs of a term to `out`: the primary ones, and if `secondary` is set
	/// the secondary ones too, each in ascending order, at most `max` in total.
	void docs(int id, boolean secondary, int max, IntList out) {
		long[] pos = { Seg.i64(index, (long) id * Layout.POSTING_ENTRY) };
		int primary = primaryCount(id);
		int total = secondary ? totalCount(id) : primary;
		int doc = 0;
		for (int i = 0; i < total && i < max; i++) {
			if (i == primary) {
				doc = 0;
			}
			doc += Seg.varint32(postings, pos);
			out.add(doc);
		}
	}

	/// Receives terms from [Terms#scan].
	@FunctionalInterface
	interface TermVisitor {

		/// @param term a reused buffer; only the first `len` bytes are the term.
		/// @return `false` to stop.
		boolean visit(int id, byte[] term, int len);

	}

}
