package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.GeoPoint;
import ai.snaphop.geon.Place;

/// Encodes places into the `DOCS` and `DOCVAR` sections and decodes them back.
///
/// The variable part of a place, in order:
///
/// ```
/// varint   place ID string ref + 1, or 0
/// byte     OSM type letter, or 0
/// zigzag   OSM ID
/// varint   name count, then per name: varint key ID, varint string ref
/// varint   house number string ref + 1, or 0
/// varint   postcode string ref + 1, or 0
/// varint   address part count, then per part: byte address type ordinal,
///          varint name count, then per name: varint key ID, varint string ref
/// varint   context count, then varint string refs
/// varint   category count, then varint string refs
/// byte     1 if a bbox follows: 4 zigzag values in 1e-7 degrees
///          (min lon, min lat, max lon, max lat)
/// varint   geometry JSON length in bytes, then the UTF-8 bytes; 0 for none
/// varint   extra JSON length in bytes, then the UTF-8 bytes; 0 for none
/// ```
///
/// A string ref is an offset into the `STRINGS` section.
final class DocCodec {

	static final int FLAG_HOUSENUMBER = 1;

	static final int FLAG_NAMED = 2;

	private DocCodec() {
	}

	/// Interns strings into the `STRINGS` section while writing.
	static final class StringPool {

		private final Map<String, Long> offsets = new HashMap<>();

		final ByteSink sink = new ByteSink(1 << 16);

		long ref(String s) {
			Long offset = offsets.get(s);
			if (offset == null) {
				offset = sink.size();
				sink.utf8(s);
				offsets.put(s, offset);
			}
			return offset;
		}

	}

	/// Assigns small integer IDs to name keys such as `default` and `en` while writing.
	static final class KeyTable {

		final Map<String, Integer> ids = new LinkedHashMap<>();

		int id(String key) {
			return ids.computeIfAbsent(key, k -> ids.size());
		}

	}

	static void encode(Place p, int tagId, long forwardOffset, StringPool strings, KeyTable keys, ByteSink docs,
			ByteSink var) {
		docs.i32(Layout.toE7(p.centroid().lon()))
			.i32(Layout.toE7(p.centroid().lat()))
			.f32((float) p.importance())
			.i32(tagId)
			.i64(var.size())
			.i64(forwardOffset)
			.i16(packCountry(p.countryCode()))
			.u8(p.addressType().ordinal())
			.u8((p.houseNumber() != null ? FLAG_HOUSENUMBER : 0) | (p.names().isEmpty() ? 0 : FLAG_NAMED))
			.i32(0);

		optionalString(var, strings, p.placeId());
		String osmType = p.osmType();
		var.u8(osmType == null || osmType.isEmpty() ? 0 : osmType.charAt(0));
		var.zigzag(p.osmId());
		names(var, strings, keys, p.names());
		optionalString(var, strings, p.houseNumber());
		optionalString(var, strings, p.postcode());
		var.varint(p.address().size());
		p.address().forEach((type, names) -> {
			var.u8(type.ordinal());
			names(var, strings, keys, names);
		});
		var.varint(p.context().size());
		p.context().forEach(c -> var.varint(strings.ref(c)));
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

	private static void names(ByteSink var, StringPool strings, KeyTable keys, Map<String, String> names) {
		var.varint(names.size());
		names.forEach((key, name) -> var.varint(keys.id(key)).varint(strings.ref(name)));
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

	/// Decodes places from an opened index. Thread safe.
	static final class Reader {

		private static final AddressType[] TYPES = AddressType.values();

		private final MemorySegment strings;

		private final MemorySegment docs;

		private final MemorySegment var;

		private final String[] keys;

		private final String[][] tags;

		Reader(MemorySegment strings, MemorySegment docs, MemorySegment var, List<String> keys, String[][] tags) {
			this.strings = strings;
			this.docs = docs;
			this.var = var;
			this.keys = keys.toArray(String[]::new);
			this.tags = tags;
		}

		long count() {
			return docs.byteSize() / Layout.DOC_RECORD;
		}

		private static long base(int doc) {
			return (long) doc * Layout.DOC_RECORD;
		}

		double lon(int doc) {
			return Layout.fromE7(Seg.i32(docs, base(doc)));
		}

		double lat(int doc) {
			return Layout.fromE7(Seg.i32(docs, base(doc) + 4));
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

		long forwardOffset(int doc) {
			return Seg.i64(docs, base(doc) + 24);
		}

		int packedCountry(int doc) {
			return Seg.i16(docs, base(doc) + 32) & 0xFFFF;
		}

		AddressType addressType(int doc) {
			return TYPES[Seg.u8(docs, base(doc) + 34)];
		}

		int flags(int doc) {
			return Seg.u8(docs, base(doc) + 35);
		}

		Place place(int doc) {
			long[] pos = { Seg.i64(docs, base(doc) + 16) };
			var b = Place.builder();
			b.placeId(optionalString(pos));
			int osmType = Seg.u8(var, pos[0]++);
			long osmId = Seg.zigzag(var, pos);
			b.osm(osmType == 0 ? null : String.valueOf((char) osmType), osmId);
			b.tag(osmKey(doc), osmValue(doc));
			b.addressType(addressType(doc));
			b.importance(importance(doc));
			b.names(names(pos));
			b.houseNumber(optionalString(pos));
			b.postcode(optionalString(pos));
			b.countryCode(unpackCountry(packedCountry(doc)));
			int parts = Seg.varint32(var, pos);
			for (int i = 0; i < parts; i++) {
				AddressType type = TYPES[Seg.u8(var, pos[0]++)];
				b.address(type, names(pos));
			}
			int contexts = Seg.varint32(var, pos);
			for (int i = 0; i < contexts; i++) {
				b.context(string(Seg.varint(var, pos)));
			}
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
			return b.build();
		}

		private Map<String, String> names(long[] pos) {
			int n = Seg.varint32(var, pos);
			var out = new LinkedHashMap<String, String>(n * 2);
			for (int i = 0; i < n; i++) {
				String key = keys[Seg.varint32(var, pos)];
				out.put(key, string(Seg.varint(var, pos)));
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
