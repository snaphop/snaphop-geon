package ai.snaphop.geon.dump;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import io.avaje.json.JsonReader;
import io.avaje.json.stream.JsonStream;

/// One element of a `Place` document exactly as the dump states it, before names and
/// addresses are interpreted.
final class RawPlace {

	private static final Pattern PLACE_ID = Pattern.compile("[0-9a-zA-Z_-]{1,60}");

	@Nullable String placeId;

	@Nullable String objectType;

	long objectId = -1;

	@Nullable String osmKey;

	@Nullable String osmValue;

	List<String> categories = List.of();

	@Nullable AddressType addressType;

	double importance;

	Map<String, String> names = Map.of();

	@Nullable String houseNumber;

	/// Address entries in input order. A key that had a list of values appears once per value.
	final List<Map.Entry<String, String>> address = new ArrayList<>();

	@Nullable String extraJson;

	@Nullable String postcode;

	@Nullable String countryCode;

	double @Nullable [] centroid;

	double @Nullable [] bbox;

	@Nullable String geometryJson;

	double @Nullable [] geometryEnvelope;

	final List<String> addressLinePlaceIds = new ArrayList<>();

	/// Parses one place object. The reader must be positioned at the object's start.
	static RawPlace parse(JsonReader r, JsonStream json, ExtraTags extraTags, boolean keepGeometry)
			throws DumpFormatException {
		var p = new RawPlace();
		r.beginObject();
		while (r.hasNextField()) {
			String field = r.nextField();
			if (r.isNullValue()) {
				continue;
			}
			switch (field) {
				case "place_id" -> p.placeId = p.readPlaceId(r);
				case "object_type" -> p.objectType = r.readString();
				case "object_id" -> p.objectId = r.readLong();
				case "osm_key" -> p.osmKey = r.readString();
				case "osm_value" -> p.osmValue = r.readString();
				case "categories" -> p.categories = Json.strings(r);
				case "address_type" -> {
					AddressType type = AddressType.ofLabelOrNull(r.readString());
					p.addressType = type == null ? AddressType.OTHER : type;
				}
				case "rank_address" -> p.addressType = AddressType.ofRank(r.readInt());
				case "importance" -> {
					double v = r.readDouble();
					if (Double.isFinite(v)) {
						p.importance = v;
					}
				}
				case "name" -> p.names = Json.stringMap(r);
				case "housenumber" -> p.houseNumber = Json.stringOrFirst(r);
				case "address" -> p.readAddress(r);
				case "extra" -> p.extraJson = readExtra(r, json, extraTags);
				case "postcode" -> p.postcode = Json.stringOrFirst(r);
				case "country_code" -> p.countryCode = r.readString();
				case "centroid" -> p.centroid = Json.numbers(r, 2, "centroid", p.where());
				case "bbox" -> p.bbox = Json.numbers(r, 4, "bbox", p.where());
				case "geometry" -> p.readGeometry(r, json, keepGeometry);
				case "addresslines" -> p.readAddressLines(r);
				default -> r.skipValue();
			}
		}
		r.endObject();
		return p;
	}

	String where() {
		return "in place " + (placeId == null ? "without place_id" : "'" + placeId + "'");
	}

	private String readPlaceId(JsonReader r) throws DumpFormatException {
		if (r.currentToken() == JsonReader.Token.NUMBER) {
			return Long.toString(r.readLong());
		}
		String id = r.readString();
		if (!PLACE_ID.matcher(id).matches()) {
			throw new DumpFormatException("Invalid place_id '" + id
					+ "': it must be 1 to 60 letters, digits, dashes or underscores (" + r.location() + ").");
		}
		return id;
	}

	private void readAddress(JsonReader r) {
		r.beginObject();
		while (r.hasNextField()) {
			String key = r.nextField();
			for (String value : Json.strings(r)) {
				address.add(Map.entry(key, value));
			}
		}
		r.endObject();
	}

	private static @Nullable String readExtra(JsonReader r, JsonStream json, ExtraTags extraTags) {
		if (!extraTags.keepsAny() || r.currentToken() != JsonReader.Token.BEGIN_OBJECT) {
			r.skipValue();
			return null;
		}
		var w = json.bufferedWriter();
		boolean any = false;
		r.beginObject();
		w.beginObject();
		while (r.hasNextField()) {
			String key = r.nextField();
			if (extraTags.keeps(key) && !r.isNullValue()) {
				w.name(key);
				Json.copy(r, w);
				any = true;
			}
			else {
				r.skipValue();
			}
		}
		r.endObject();
		w.endObject();
		String result = w.result();
		return any ? result : null;
	}

	private void readGeometry(JsonReader r, JsonStream json, boolean keep) {
		if (!keep && bbox != null) {
			// The geometry would only give the bounding box, and the dump already has it.
			// Geometries are most of a dump's bytes, so skipping them speeds up imports.
			r.skipValue();
			return;
		}
		double[] envelope = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
				Double.NEGATIVE_INFINITY };
		if (keep) {
			var w = json.bufferedWriter();
			Json.geometry(r, envelope, w);
			geometryJson = w.result();
		}
		else {
			Json.geometry(r, envelope, null);
		}
		if (envelope[0] <= envelope[2]) {
			geometryEnvelope = envelope;
		}
	}

	private void readAddressLines(JsonReader r) {
		r.beginArray();
		while (r.hasNextElement()) {
			String id = null;
			boolean isAddress = false;
			r.beginObject();
			while (r.hasNextField()) {
				String field = r.nextField();
				if (r.isNullValue()) {
					continue;
				}
				switch (field) {
					case "place_id" -> id = r.currentToken() == JsonReader.Token.NUMBER ? Long.toString(r.readLong())
							: r.readString();
					case "isaddress" -> isAddress = r.readBoolean();
					default -> r.skipValue();
				}
			}
			r.endObject();
			if (isAddress && id != null) {
				addressLinePlaceIds.add(id);
			}
		}
		r.endArray();
	}

}
