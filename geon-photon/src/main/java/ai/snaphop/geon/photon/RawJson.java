package ai.snaphop.geon.photon;

import io.avaje.json.JsonReader;
import io.avaje.json.JsonWriter;

/// Embeds already encoded JSON in a response.
///
/// avaje-json 3.16's `JsonWriter.rawValue` writes each character as a single byte,
/// which corrupts non-ASCII text. ASCII JSON (geometries, most extra tags) is written
/// raw; anything else is parsed and copied value by value so it stays valid UTF-8.
final class RawJson {

	private RawJson() {
	}

	static void write(JsonWriter w, String json) {
		if (isAscii(json)) {
			w.rawValue(json);
			return;
		}
		try (JsonReader r = PhotonError.JSON.reader(json)) {
			copy(r, w);
		}
	}

	static boolean isAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) >= 0x80) {
				return false;
			}
		}
		return true;
	}

	private static void copy(JsonReader r, JsonWriter w) {
		switch (r.currentToken()) {
			case BEGIN_OBJECT -> {
				r.beginObject();
				w.beginObject();
				while (r.hasNextField()) {
					w.name(r.nextField());
					copy(r, w);
				}
				r.endObject();
				w.endObject();
			}
			case BEGIN_ARRAY -> {
				r.beginArray();
				w.beginArray();
				while (r.hasNextElement()) {
					copy(r, w);
				}
				r.endArray();
				w.endArray();
			}
			case STRING -> w.value(r.readString());
			case BOOLEAN -> w.value(r.readBoolean());
			case NULL -> {
				r.isNullValue();
				w.nullValue();
			}
			default -> w.value(r.readDecimal());
		}
	}

}
