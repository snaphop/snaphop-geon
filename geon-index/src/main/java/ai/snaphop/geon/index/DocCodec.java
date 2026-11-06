package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.GeoPoint;
import ai.snaphop.geon.Place;

/// Encodes places into the `DOCS`, `DOCVAR` and `ADDRESS` sections and decodes them.
///
/// ## A place in `DOCVAR`
///
/// ```
/// term list  the place's own terms (see Layout)
/// varint     address set ID
/// byte       place ID kind: 0 none, 1 a number (varint follows), 2 a string
///            (varint string ref follows)
/// byte       OSM type letter, or 0
/// zigzag     OSM ID
/// names      varint count, then per name: varint key ID, varint string ref
/// varint     house number string ref + 1, or 0
/// varint     category count, then varint string refs
/// byte       1 if a bbox follows: 4 zigzag values in 1e-7 degrees
///            (min lon, min lat, max lon, max lat)
/// varint     geometry JSON length in bytes, then the UTF-8 bytes; 0 for none
/// varint     extra JSON length in bytes, then the UTF-8 bytes; 0 for none
/// ```
///
/// ## An address set in `ADDRESS`
///
/// ```
/// term list  the address terms
/// varint     part count, then per part: byte address type ordinal, names
/// varint     context count, then varint string refs
/// varint     postcode string ref + 1, or 0
/// ```
///
/// A string ref is an offset into the `STRINGS` section.
final class DocCodec {

	static final int FLAG_HOUSENUMBER = 1;

	static final int FLAG_NAMED = 2;

	/// Size of the fixed part of a spill record, see [#writeSpill].
	static final int SPILL_FIXED = 24;

	private DocCodec() {
	}

	/// Interns strings into the `STRINGS` section while writing.
	static final class StringPool {

		private final Map<String, Long> offsets = new HashMap<>();

		final ByteSink sink;

		StringPool(ByteSink sink) {
			this.sink = sink;
		}

		long ref(String s) {
			Long offset = offsets.get(s);
			if (offset == null) {
				offset = sink.size();
				sink.utf8(s);
				offsets.put(s, offset);
			}
			return offset;
		}

		int count() {
			return offsets.size();
		}

	}

	/// Assigns small integer IDs to name keys such as `default` and `en` while writing.
	static final class KeyTable {

		final Map<String, Integer> ids = new LinkedHashMap<>();

		int id(String key) {
			return ids.computeIfAbsent(key, k -> ids.size());
		}

	}

	/// Writes a place to the import's temporary spill file:
	///
	/// ```
	///  0 int    longitude * 1e7
	///  4 int    latitude * 1e7
	///  8 float  importance
	/// 12 int    tag ID
	/// 16 short  country code
	/// 18 byte   address type ordinal
	/// 19 byte   flags
	/// 20 int    length of the DOCVAR part that follows (without the term list)
	/// ```
	static void writeSpill(Place p, int tagId, int setId, StringPool strings, KeyTable keys, ByteSink spill,
			ByteSink scratch) {
		scratch.clearMemory();
		encodeVar(p, setId, strings, keys, scratch);
		spill.i32(Layout.toE7(p.centroid().lon()))
			.i32(Layout.toE7(p.centroid().lat()))
			.f32((float) p.importance())
			.i32(tagId)
			.i16(packCountry(p.countryCode()))
			.u8(p.addressType().ordinal())
			.u8((p.houseNumber() != null ? FLAG_HOUSENUMBER : 0) | (p.names().isEmpty() ? 0 : FLAG_NAMED))
			.i32((int) scratch.size());
		scratch.copyTo(spill);
	}

	private static void encodeVar(Place p, int setId, StringPool strings, KeyTable keys, ByteSink var) {
		var.varint(setId);
		placeId(var, strings, p.placeId());
		String osmType = p.osmType();
		var.u8(osmType == null || osmType.isEmpty() ? 0 : osmType.charAt(0));
		var.zigzag(p.osmId());
		names(var, strings, keys, p.names());
		optionalString(var, strings, p.houseNumber());
		var.varint(p.categories().size());
		p.categories().forEach(c -> var.varint(strings.ref(c)));
		BBox bbox = p.bbox();
		if (bbox == null) {
			var.u8(0);
		}
		else {
			var.u8(1)
				.zigzag(Layout.toE7(bbox.minLon()))
				.zigzag(Layout.toE7(bbox.minLat()))
				.zigzag(Layout.toE7(bbox.maxLon()))
				.zigzag(Layout.toE7(bbox.maxLat()));
		}
		inlineString(var, p.geometryJson());
		inlineString(var, p.extraJson());
	}

	/// Writes an address set after its term list.
	static void encodeAddress(Map<AddressType, Map<String, String>> parts, Set<String> context,
			@Nullable String postcode, StringPool strings, KeyTable keys, ByteSink out) {
		out.varint(parts.size());
		parts.forEach((type, names) -> {
			out.u8(type.ordinal());
			names(out, strings, keys, names);
		});
		out.varint(context.size());
		context.forEach(c -> out.varint(strings.ref(c)));
		optionalString(out, strings, postcode);
	}

	private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("[1-9][0-9]{0,17}");

	/// Place IDs are usually numbers; those are stored as numbers rather than strings.
	private static void placeId(ByteSink var, StringPool strings, @Nullable String id) {
		if (id == null) {
			var.u8(0);
		}
		else if (NUMBER.matcher(id).matches()) {
			var.u8(1).varint(Long.parseLong(id));
		}
		else {
			var.u8(2).varint(strings.ref(id));
		}
	}

	/// Writes a term list. `entries` hold term ID in the high and mask in the low 32
	/// bits, and are sorted in place.
	static void writeTerms(long[] entries, int count, ByteSink out) {
		java.util.Arrays.sort(entries, 0, count);
		out.varint(count);
		int previous = 0;
		for (int i = 0; i < count; i++) {
			int termId = (int) (entries[i] >>> 32);
			out.varint(termId - previous).varint((int) entries[i]);
			previous = termId;
		}
	}

	private static void names(ByteSink out, StringPool strings, KeyTable keys, Map<String, String> names) {
		out.varint(names.size());
		names.forEach((key, name) -> out.varint(keys.id(key)).varint(strings.ref(name)));
	}

	private static void optionalString(ByteSink var, StringPool strings, @Nullable String s) {
		var.varint(s == null ? 0 : strings.ref(s) + 1);
	}

	private static void inlineString(ByteSink var, @Nullable String s) {
		if (s == null) {
			var.varint(0);
		}
		else {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			var.varint(b.length).bytes(b);
		}
	}

	static int packCountry(@Nullable String code) {
		if (code == null || code.length() != 2) {
			return 0;
		}
		return (code.charAt(0) & 0xFF) | ((code.charAt(1) & 0xFF) << 8);
	}

	static @Nullable String unpackCountry(int packed) {
		if (packed == 0) {
			return null;
		}
		return new String(new char[] { (char) (packed & 0xFF), (char) ((packed >>> 8) & 0xFF) });
	}

	/// Skips a term list at `pos[0]`.
	static void skipTerms(MemorySegment s, long[] pos) {
		int n = Seg.varint32(s, pos);
		for (int i = 0; i < 2 * n; i++) {
			Seg.varint(s, pos);
		}
	}

	/// Decodes places from an opened index. Thread safe.
	static final class Reader {

		private static final AddressType[] TYPES = AddressType.values();

		private final MemorySegment strings;

		private final MemorySegment docs;

		private final MemorySegment var;

		private final MemorySegment addressIndex;

		private final MemorySegment address;

		private final String[] keys;

		private final String[][] tags;

		Reader(MemorySegment strings, MemorySegment docs, MemorySegment var, MemorySegment addressIndex,
				MemorySegment address, List<String> keys, String[][] tags) {
			this.strings = strings;
			this.docs = docs;
			this.var = var;
			this.addressIndex = addressIndex;
			this.address = address;
			this.keys = keys.toArray(String[]::new);
			this.tags = tags;
		}

		long count() {
			return docs.byteSize() / Layout.DOC_RECORD;
		}

		private static long base(int doc) {
			return (long) doc * Layout.DOC_RECORD;
		}

		int lonE7(int doc) {
			return Seg.i32(docs, base(doc));
		}

		int latE7(int doc) {
			return Seg.i32(docs, base(doc) + 4);
		}

		double lon(int doc) {
			return Layout.fromE7(lonE7(doc));
		}

		double lat(int doc) {
			return Layout.fromE7(latE7(doc));
		}

		float importance(int doc) {
			return Seg.f32(docs, base(doc) + 8);
		}

		int tagId(int doc) {
			return Seg.i32(docs, base(doc) + 12);
		}

		String osmKey(int doc) {
			return tags[tagId(doc)][0];
		}

		String osmValue(int doc) {
			return tags[tagId(doc)][1];
		}

		/// Offset of the doc's own term list in the `DOCVAR` segment.
		long varOffset(int doc) {
			return Seg.i64(docs, base(doc) + 16);
		}

		int packedCountry(int doc) {
			return Seg.i16(docs, base(doc) + 24) & 0xFFFF;
		}

		AddressType addressType(int doc) {
			return TYPES[Seg.u8(docs, base(doc) + 26)];
		}

		int flags(int doc) {
			return Seg.u8(docs, base(doc) + 27);
		}

		MemorySegment varSegment() {
			return var;
		}

		MemorySegment addressSegment() {
			return address;
		}

		/// Offset of an address set's term list in the `ADDRESS` segment.
		long addressOffset(int setId) {
			return Seg.i64(addressIndex, 8L * setId);
		}

		/// The doc's address set ID, read after its own term list.
		int addressSet(int doc) {
			long[] pos = { varOffset(doc) };
			skipTerms(var, pos);
			return Seg.varint32(var, pos);
		}

		Place place(int doc) {
			long[] pos = { varOffset(doc) };
			skipTerms(var, pos);
			int setId = Seg.varint32(var, pos);
			var b = Place.builder();
			b.placeId(switch (Seg.u8(var, pos[0]++)) {
				case 0 -> null;
				case 1 -> Long.toString(Seg.varint(var, pos));
				default -> string(Seg.varint(var, pos));
			});
			int osmType = Seg.u8(var, pos[0]++);
			long osmId = Seg.zigzag(var, pos);
			b.osm(osmType == 0 ? null : String.valueOf((char) osmType), osmId);
			b.tag(osmKey(doc), osmValue(doc));
			b.addressType(addressType(doc));
			b.importance(importance(doc));
			b.names(names(var, pos));
			b.houseNumber(optionalString(pos));
			b.countryCode(unpackCountry(packedCountry(doc)));
			int categories = Seg.varint32(var, pos);
			for (int i = 0; i < categories; i++) {
				b.category(string(Seg.varint(var, pos)));
			}
			b.centroid(new GeoPoint(lon(doc), lat(doc)));
			if (Seg.u8(var, pos[0]++) == 1) {
				double minLon = Layout.fromE7((int) Seg.zigzag(var, pos));
				double minLat = Layout.fromE7((int) Seg.zigzag(var, pos));
				double maxLon = Layout.fromE7((int) Seg.zigzag(var, pos));
				double maxLat = Layout.fromE7((int) Seg.zigzag(var, pos));
				b.bbox(new BBox(minLon, minLat, maxLon, maxLat));
			}
			b.geometryJson(inlineString(pos));
			b.extraJson(inlineString(pos));
			readAddress(setId, b);
			return b.build();
		}

		private void readAddress(int setId, Place.Builder b) {
			long[] pos = { addressOffset(setId) };
			skipTerms(address, pos);
			int parts = Seg.varint32(address, pos);
			for (int i = 0; i < parts; i++) {
				AddressType type = TYPES[Seg.u8(address, pos[0]++)];
				b.address(type, names(address, pos));
			}
			int contexts = Seg.varint32(address, pos);
			for (int i = 0; i < contexts; i++) {
				b.context(string(Seg.varint(address, pos)));
			}
			long postcode = Seg.varint(address, pos);
			b.postcode(postcode == 0 ? null : string(postcode - 1));
		}

		private Map<String, String> names(MemorySegment s, long[] pos) {
			int n = Seg.varint32(s, pos);
			var out = new LinkedHashMap<String, String>(n * 2);
			for (int i = 0; i < n; i++) {
				String key = keys[Seg.varint32(s, pos)];
				out.put(key, string(Seg.varint(s, pos)));
			}
			return out;
		}

		private @Nullable String optionalString(long[] pos) {
			long ref = Seg.varint(var, pos);
			return ref == 0 ? null : string(ref - 1);
		}

		private @Nullable String inlineString(long[] pos) {
			int len = Seg.varint32(var, pos);
			if (len == 0) {
				return null;
			}
			String s = Seg.utf8(var, pos[0], len);
			pos[0] += len;
			return s;
		}

		String string(long ref) {
			long[] p = { ref };
			return Seg.utf8(strings, p);
		}

	}

}
