package ai.snaphop.geon.dump;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.avaje.json.JsonReader;
import io.avaje.json.JsonWriter;

/// Small pull parsing helpers on top of avaje's [JsonReader].
final class Json {

	private Json() {
	}

	/// Reads a string, or the first string of an array of strings. Anything else is skipped.
	static @Nullable String stringOrFirst(JsonReader r) {
		var token = r.currentToken();
		if (token == JsonReader.Token.STRING) {
			return r.readString();
		}
		if (token == JsonReader.Token.BEGIN_ARRAY) {
			List<String> all = strings(r);
			return all.isEmpty() ? null : all.getFirst();
		}
		r.skipValue();
		return null;
	}

	/// Reads a string or an array of strings into a list. Non string elements are skipped.
	static List<String> strings(JsonReader r) {
		var out = new ArrayList<String>(2);
		var token = r.currentToken();
		if (token == JsonReader.Token.STRING) {
			out.add(r.readString());
		}
		else if (token == JsonReader.Token.BEGIN_ARRAY) {
			r.beginArray();
			while (r.hasNextElement()) {
				if (r.currentToken() == JsonReader.Token.STRING) {
					out.add(r.readString());
				}
				else {
					r.skipValue();
				}
			}
			r.endArray();
		}
		else {
			r.skipValue();
		}
		return out;
	}

	/// Reads an object whose values are strings, or arrays of strings of which the first is
	/// kept. Other values are skipped. A `null` reads as an empty map.
	static Map<String, String> stringMap(JsonReader r) {
		var out = new LinkedHashMap<String, String>();
		if (r.isNullValue()) {
			return out;
		}
		r.beginObject();
		while (r.hasNextField()) {
			String key = r.nextField();
			String value = stringOrFirst(r);
			if (value != null) {
				out.put(key, value);
			}
		}
		r.endObject();
		return out;
	}

	/// Reads an array of exactly `n` numbers.
	static double[] numbers(JsonReader r, int n, String field, String where) throws DumpFormatException {
		double[] out = new double[n];
		int i = 0;
		r.beginArray();
		while (r.hasNextElement()) {
			if (i >= n || r.currentToken() != JsonReader.Token.NUMBER) {
				throw new DumpFormatException("Invalid " + field + ": expected an array of " + n + " numbers " + where
						+ " (" + r.location() + ").");
			}
			out[i++] = r.readDouble();
		}
		r.endArray();
		if (i != n) {
			throw new DumpFormatException("Invalid " + field + ": expected an array of " + n + " numbers but got " + i
					+ " " + where + " (" + r.location() + ").");
		}
		return out;
	}

	/// Copies any JSON value from the reader to the writer.
	static void copy(JsonReader r, JsonWriter w) {
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

	/// Reads a GeoJSON geometry, tracking its envelope and optionally copying it.
	///
	/// Every innermost array of numbers inside a `coordinates` member counts as a
	/// position whose first two numbers are longitude and latitude.
	///
	/// @param envelope `[minLon, minLat, maxLon, maxLat]`, widened in place. Start it at
	///   `[+inf, +inf, -inf, -inf]`.
	/// @param w where to copy the geometry, or `null` to only compute the envelope.
	static void geometry(JsonReader r, double[] envelope, @Nullable JsonWriter w) {
		geometryValue(r, envelope, w, false);
	}

	private static void geometryValue(JsonReader r, double[] envelope, @Nullable JsonWriter w,
			boolean inCoordinates) {
		switch (r.currentToken()) {
			case BEGIN_OBJECT -> {
				r.beginObject();
				if (w != null) {
					w.beginObject();
				}
				while (r.hasNextField()) {
					String name = r.nextField();
					if (w != null) {
						w.name(name);
					}
					geometryValue(r, envelope, w, inCoordinates || name.equals("coordinates"));
				}
				r.endObject();
				if (w != null) {
					w.endObject();
				}
			}
			case BEGIN_ARRAY -> {
				r.beginArray();
				if (w != null) {
					w.beginArray();
				}
				int index = 0;
				double lon = 0;
				while (r.hasNextElement()) {
					if (inCoordinates && r.currentToken() == JsonReader.Token.NUMBER) {
						double v = r.readDouble();
						if (w != null) {
							w.value(v);
						}
						if (index == 0) {
							lon = v;
						}
						else if (index == 1) {
							envelope[0] = Math.min(envelope[0], lon);
							envelope[1] = Math.min(envelope[1], v);
							envelope[2] = Math.max(envelope[2], lon);
							envelope[3] = Math.max(envelope[3], v);
						}
						index++;
					}
					else {
						geometryValue(r, envelope, w, inCoordinates);
					}
				}
				r.endArray();
				if (w != null) {
					w.endArray();
				}
			}
			default -> {
				if (w != null) {
					copy(r, w);
				}
				else {
					r.skipValue();
				}
			}
		}
	}

}
