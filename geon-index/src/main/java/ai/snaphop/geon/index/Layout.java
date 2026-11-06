package ai.snaphop.geon.index;

import java.nio.charset.StandardCharsets;

/// The binary layout of an index file. All numbers are little endian.
///
/// ```
/// header   magic "GEONIDX1" (8 bytes), format version (int), section count (int),
///          then per section: offset (long), length (long)
/// sections in Section order, each 8 byte aligned
/// ```
///
/// ## Sections
///
/// * `META`: name keys, languages, data timestamp, flags and counts.
/// * `STRINGS`: interned strings, each a varint length and UTF-8 bytes. Other
///   sections refer to a string by its offset here.
/// * `TAGS`: the distinct OSM key and value pairs, two string offsets (longs) each.
/// * `DOCS`: one fixed [#DOC_RECORD] byte record per place, in doc ID order. Doc IDs
///   are assigned by descending importance.
/// * `DOCVAR`: the variable part of each place: its own terms, then its address set,
///   then its fields; see [DocCodec].
/// * `ADDRESS_INDEX`: the offset (long) of each address set in `ADDRESS`.
/// * `ADDRESS`: address sets: the address parts and context names that many places
///   share, with their terms; see [DocCodec].
/// * `TERMS`: the sorted term dictionary in front coded blocks of [#TERM_BLOCK] terms.
/// * `TERM_BLOCKS`: the offset (long) of each block in `TERMS`.
/// * `POSTING_INDEX`: per term ID, a [#POSTING_ENTRY] byte entry: offset (long) into
///   `POSTINGS`, primary count (int), total count (int).
/// * `POSTINGS`: per term, delta encoded varint doc IDs: the primary docs first, then
///   the secondary docs, each list ascending.
/// * `RTREE`: a packed Hilbert R-tree over place centroids, see [PackedRTree].
///
/// A term list ("forward" entries) is a varint count, then (term ID delta, field mask)
/// varint pairs in ascending term ID order. Each place has one for its own terms and
/// shares the one of its address set.
final class Layout {

	private Layout() {
	}

	static final byte[] MAGIC = "GEONIDX1".getBytes(StandardCharsets.US_ASCII);

	static final int VERSION = 3;

	static final int HEADER_FIXED = 16;

	static final int SECTION_ENTRY = 16;

	/// Size of a `DOCS` record:
	///
	/// ```
	///  0 int    longitude * 1e7
	///  4 int    latitude * 1e7
	///  8 float  importance
	/// 12 int    tag ID (index into TAGS)
	/// 16 long   offset into DOCVAR
	/// 24 short  country code, two ASCII letters, 0 if none
	/// 26 byte   address type ordinal
	/// 27 byte   flags, see DocCodec
	/// ```
	static final int DOC_RECORD = 28;

	static final int TERM_BLOCK = 32;

	static final int POSTING_ENTRY = 16;

	/// Coordinates are stored as integers in units of 1e-7 degrees, about 1 cm.
	static final double COORD_SCALE = 1e7;

	enum Section {
		META, STRINGS, TAGS, DOCS, DOCVAR, ADDRESS_INDEX, ADDRESS, TERMS, TERM_BLOCKS, POSTING_INDEX, POSTINGS, RTREE
	}

	static int toE7(double degrees) {
		return (int) Math.round(degrees * COORD_SCALE);
	}

	static double fromE7(int e7) {
		return e7 / COORD_SCALE;
	}

}
