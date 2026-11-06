package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// Reads a doc's terms and their field masks: its own term list merged with the term
/// list of its address set.
///
/// The term lists let a query check candidate docs against every query token without
/// intersecting large postings lists. Thread safe; the [Entries] it fills are not.
final class Forward {

	private final DocCodec.Reader docs;

	Forward(DocCodec.Reader docs) {
		this.docs = docs;
	}

	/// A reusable buffer holding one doc's terms, sorted by term ID.
	static final class Entries {

		int[] termIds = new int[64];

		int[] masks = new int[64];

		int size;

		private int[] ownIds = new int[32];

		private int[] ownMasks = new int[32];

		/// The last address set decoded. Candidates arrive in doc ID order, and houses of
		/// one street are mostly adjacent there, so consecutive docs often share it.
		private int setId = -1;

		private int[] setIds = new int[64];

		private int[] setMasks = new int[64];

		private int setSize;

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

		private void ensure(int n) {
			if (termIds.length < n) {
				termIds = Arrays.copyOf(termIds, n);
				masks = Arrays.copyOf(masks, n);
			}
		}

	}

	void read(int doc, Entries out) {
		MemorySegment var = docs.varSegment();
		long[] pos = { docs.varOffset(doc) };
		int own = Seg.varint32(var, pos);
		if (out.ownIds.length < own) {
			out.ownIds = new int[own];
			out.ownMasks = new int[own];
		}
		int termId = 0;
		for (int i = 0; i < own; i++) {
			termId += Seg.varint32(var, pos);
			out.ownIds[i] = termId;
			out.ownMasks[i] = Seg.varint32(var, pos);
		}
		int setId = Seg.varint32(var, pos);
		if (setId != out.setId) {
			MemorySegment address = docs.addressSegment();
			long[] apos = { docs.addressOffset(setId) };
			int shared = Seg.varint32(address, apos);
			if (out.setIds.length < shared) {
				out.setIds = new int[shared];
				out.setMasks = new int[shared];
			}
			int id = 0;
			for (int j = 0; j < shared; j++) {
				id += Seg.varint32(address, apos);
				out.setIds[j] = id;
				out.setMasks[j] = Seg.varint32(address, apos);
			}
			out.setSize = shared;
			out.setId = setId;
		}
		int shared = out.setSize;
		out.ensure(own + shared);
		// Merge the two sorted lists; a term in both gets both masks.
		int i = 0;
		int n = 0;
		for (int j = 0; j < shared; j++) {
			int sharedId = out.setIds[j];
			int sharedMask = out.setMasks[j];
			while (i < own && out.ownIds[i] < sharedId) {
				out.termIds[n] = out.ownIds[i];
				out.masks[n++] = out.ownMasks[i++];
			}
			if (i < own && out.ownIds[i] == sharedId) {
				out.termIds[n] = sharedId;
				out.masks[n++] = sharedMask | out.ownMasks[i++];
			}
			else {
				out.termIds[n] = sharedId;
				out.masks[n++] = sharedMask;
			}
		}
		while (i < own) {
			out.termIds[n] = out.ownIds[i];
			out.masks[n++] = out.ownMasks[i++];
		}
		out.size = n;
	}

}
