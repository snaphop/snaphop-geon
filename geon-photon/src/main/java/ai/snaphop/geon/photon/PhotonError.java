package ai.snaphop.geon.photon;

import io.avaje.json.stream.JsonStream;

/// A request that Photon would reject, with the exact JSON body Photon sends.
///
/// Photon produces two shapes of error body:
///
/// * its own checks: `{"message": "..."}`
/// * parameter validation by its web framework, Javalin:
///   `{"<param>": [{"message": "...", "args": {}, "value": <value>}]}`, where `value`
///   is the parameter as parsed when a check failed, or the raw text when it could not
///   be parsed at all (message `TYPE_CONVERSION_FAILED`).
final class PhotonError extends RuntimeException {

	private static final long serialVersionUID = 1L;

	static final JsonStream JSON = JsonStream.builder().build();

	/// The message Javalin uses when a parameter cannot be parsed.
	static final String TYPE_CONVERSION_FAILED = "TYPE_CONVERSION_FAILED";

	private final int status;

	private final String body;

	private PhotonError(int status, String message, String body) {
		super(message, null, false, false);
		this.status = status;
		this.body = body;
	}

	int status() {
		return status;
	}

	String body() {
		return body;
	}

	/// An error with Photon's `{"message": ...}` body.
	static PhotonError message(int status, String message) {
		var w = JSON.bufferedWriter();
		w.beginObject();
		w.name("message");
		w.value(message);
		w.endObject();
		return new PhotonError(status, message, w.result());
	}

	/// A validation error with Javalin's body.
	/// @param valueJson the offending value already encoded as JSON.
	static PhotonError invalid(String param, String message, String valueJson) {
		var w = JSON.bufferedWriter();
		w.beginObject();
		w.name(param);
		w.beginArray();
		w.beginObject();
		w.name("message");
		w.value(message);
		w.name("args");
		w.beginObject();
		w.endObject();
		w.name("value");
		RawJson.write(w, valueJson);
		w.endObject();
		w.endArray();
		w.endObject();
		return new PhotonError(400, param + ": " + message, w.result());
	}

	/// A parameter whose text could not be parsed.
	static PhotonError unparsable(String param, String raw) {
		return invalid(param, TYPE_CONVERSION_FAILED, string(raw));
	}

	static String string(String s) {
		var w = JSON.bufferedWriter();
		w.value(s);
		return w.result();
	}

	static String strings(java.util.List<String> values) {
		var w = JSON.bufferedWriter();
		w.beginArray();
		values.forEach(w::value);
		w.endArray();
		return w.result();
	}

	/// Formats a double the way Java and Jackson print it, for example `100.0`.
	static String number(double v) {
		return Double.toString(v);
	}

}
