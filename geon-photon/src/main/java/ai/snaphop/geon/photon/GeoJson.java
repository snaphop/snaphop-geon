package ai.snaphop.geon.photon;

import java.util.List;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.Hit;
import ai.snaphop.geon.Language;
import ai.snaphop.geon.Place;
import io.avaje.json.JsonWriter;

/// Writes results as Photon's GeocodeJSON flavoured GeoJSON.
///
/// Properties appear in Photon's order and are omitted when empty:
/// `osm_type`, `osm_id`, `osm_key`, `osm_value`, `type`, `housenumber`, `name`,
/// `street`, `locality`, `district`, `city`, `county`, `state`, `country`,
/// `postcode`, `countrycode`, `extra`, `extent`.
///
/// `extent` is `[min lon, max lat, max lon, min lat]`, the north west and south east
/// corners, as Photon writes it.
final class GeoJson {

	private static final AddressType[] ADDRESS = { AddressType.STREET, AddressType.LOCALITY, AddressType.DISTRICT,
			AddressType.CITY, AddressType.COUNTY, AddressType.STATE, AddressType.COUNTRY };

	private GeoJson() {
	}

	static byte[] write(List<Hit> hits, PhotonRequests.Format format) {
		var w = PhotonError.JSON.bufferedWriterAsBytes();
		w.beginObject();
		w.name("type");
		w.value("FeatureCollection");
		w.name("features");
		w.beginArray();
		for (Hit hit : hits) {
			feature(w, hit.place(), format);
		}
		w.endArray();
		if (format.debug()) {
			w.name("properties");
			w.beginObject();
			w.name("debug");
			w.beginArray();
			for (Hit hit : hits) {
				w.beginObject();
				w.name("score");
				w.value(hit.score());
				w.name("importance");
				w.value(hit.place().importance());
				if (hit.explanation() != null) {
					w.name("explanation");
					w.value(hit.explanation());
				}
				w.endObject();
			}
			w.endArray();
			w.endObject();
		}
		w.endObject();
		return w.result();
	}

	private static void feature(JsonWriter w, Place p, PhotonRequests.Format format) {
		Language lang = format.language();
		w.beginObject();
		w.name("type");
		w.value("Feature");
		w.name("properties");
		w.beginObject();
		if (p.osmType() != null) {
			w.name("osm_type");
			w.value(p.osmType());
			w.name("osm_id");
			w.value(p.osmId());
		}
		put(w, "osm_key", p.osmKey());
		put(w, "osm_value", p.osmValue());
		put(w, "type", p.addressType().label());
		put(w, "housenumber", p.houseNumber());
		put(w, "name", p.displayNameOrNull(lang.preferred(), lang.fallback()));
		for (AddressType type : ADDRESS) {
			put(w, type.label(), p.addressNameOrNull(type, lang.preferred(), lang.fallback()));
		}
		put(w, "postcode", p.postcode());
		put(w, "countrycode", p.countryCode());
		if (p.extraJson() != null) {
			w.name("extra");
			RawJson.write(w, p.extraJson());
		}
		BBox b = p.bbox();
		if (b != null && !b.isEmpty()) {
			w.name("extent");
			w.beginArray();
			w.value(b.minLon());
			w.value(b.maxLat());
			w.value(b.maxLon());
			w.value(b.minLat());
			w.endArray();
		}
		w.endObject();
		w.name("geometry");
		if (format.geometry() && p.geometryJson() != null) {
			RawJson.write(w, p.geometryJson());
		}
		else {
			w.beginObject();
			w.name("type");
			w.value("Point");
			w.name("coordinates");
			w.beginArray();
			w.value(p.centroid().lon());
			w.value(p.centroid().lat());
			w.endArray();
			w.endObject();
		}
		w.endObject();
	}

	private static void put(JsonWriter w, String name, @Nullable String value) {
		if (value != null) {
			w.name(name);
			w.value(value);
		}
	}

}
