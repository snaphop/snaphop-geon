package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// Reads the forward index: for each doc, its term IDs and their field masks.
///
/// The forward index lets a query check candidate docs against every query token
/// without intersecting large postings lists. Thread safe; the [Entries] it fills
/// are not.
final class Forward {

	private final MemorySegment s;

	private final DocCodec.Reader docs;

	Forward(MemorySegment s, DocCodec.Reader docs) {
		this.s = s;
		this.docs = docs;
	}

	/// A reusable buffer holding one doc's terms, sorted by term ID.
	static final class Entries {

		int[] termIds = new int[64];

		int[] masks = new int[64];

		int size;

		/// The index of the first entry with a term ID of at least `termId`.
		int lowerBound(int termId) {
			int lo = 0;
			int hi = size;
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (termIds[mid] < termId) {
					lo = mid + 1;
				}
				else {
					hi = mid;
				}
			}
			return lo;
		}

		/// The mask of a term, or 0 if the doc does not have it.
		int mask(int termId) {
			int i = lowerBound(termId);
			return i < size && termIds[i] == termId ? masks[i] : 0;
		}

	}

	void read(int doc, Entries out) {
		long[] pos = { docs.forwardOffset(doc) };
		int n = Seg.varint32(s, pos);
		if (out.termIds.length < n) {
			out.termIds = Arrays.copyOf(out.termIds, n);
			out.masks = Arrays.copyOf(out.masks, n);
		}
		int termId = 0;
		for (int i = 0; i < n; i++) {
			termId += Seg.varint32(s, pos);
			out.termIds[i] = termId;
			out.masks[i] = Seg.varint32(s, pos);
		}
		out.size = n;
	}

}
