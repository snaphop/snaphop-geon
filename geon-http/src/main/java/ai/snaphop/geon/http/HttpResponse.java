package ai.snaphop.geon.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/// An HTTP response produced by a handler.
///
/// @param status the status code.
/// @param contentType the `Content-Type` header value.
/// @param body the body bytes.
/// @param headers extra headers.
public record HttpResponse(int status, String contentType, byte[] body, Map<String, String> headers) {

	/// The JSON content type used by Geon's APIs.
	public static final String JSON = "application/json";

	/// A JSON response.
	/// @param status the status code.
	/// @param json the JSON text.
	/// @return the response.
	public static HttpResponse json(int status, String json) {
		return new HttpResponse(status, JSON, json.getBytes(StandardCharsets.UTF_8), Map.of());
	}

	/// A JSON response from bytes that are already UTF-8 JSON.
	/// @param status the status code.
	/// @param json the JSON bytes.
	/// @return the response.
	public static HttpResponse json(int status, byte[] json) {
		return new HttpResponse(status, JSON, json, Map.of());
	}

	/// A plain text response.
	/// @param status the status code.
	/// @param contentType the content type.
	/// @param text the text.
	/// @return the response.
	public static HttpResponse text(int status, String contentType, String text) {
		return new HttpResponse(status, contentType, text.getBytes(StandardCharsets.UTF_8), Map.of());
	}

}
