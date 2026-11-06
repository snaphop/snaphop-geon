package ai.snaphop.geon.http;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/// An HTTP request as a handler sees it.
///
/// @param method the request method in upper case, for example `GET`.
/// @param path the decoded path without query string and without a trailing slash,
///   except for the root path `/`.
/// @param query the decoded query parameters in the order they first appear. A
///   parameter given several times keeps every value in order.
/// @param headers looks up a request header by case insensitive name.
public record HttpRequest(String method, String path, Map<String, List<String>> query,
		Function<String, @Nullable String> headers) {

	/// The first value of a query parameter.
	/// @param name the parameter name.
	/// @return the value or `null` if absent.
	public @Nullable String param(String name) {
		List<String> values = query.get(name);
		return values == null || values.isEmpty() ? null : values.getFirst();
	}

	/// All values of a query parameter.
	/// @param name the parameter name.
	/// @return the values, empty if absent.
	public List<String> params(String name) {
		return query.getOrDefault(name, List.of());
	}

	/// A request header.
	/// @param name the header name, any case.
	/// @return the value or `null` if absent.
	public @Nullable String header(String name) {
		return headers.apply(name);
	}

}
