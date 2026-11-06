package ai.snaphop.geon.http;

import java.util.List;

import org.jspecify.annotations.Nullable;

/// Which browser origins may call the API.
///
/// @param anyOrigin allow every origin.
/// @param origins allowed origins such as `https://example.com`. An origin given
///   without a scheme allows both `http` and `https`.
public record Cors(boolean anyOrigin, List<String> origins) {

	/// No cross origin access. This is the default.
	public static final Cors NONE = new Cors(false, List.of());

	/// Any origin.
	public static final Cors ANY = new Cors(true, List.of());

	/// Copies the origins and normalizes those without a scheme.
	public Cors {
		origins = origins.stream()
			.flatMap(o -> o.startsWith("http") ? java.util.stream.Stream.of(o)
					: java.util.stream.Stream.of("http://" + o, "https://" + o))
			.toList();
	}

	/// Whether any cross origin access is enabled.
	/// @return `true` if enabled.
	public boolean isEnabled() {
		return anyOrigin || !origins.isEmpty();
	}

	/// The `Access-Control-Allow-Origin` value for a request origin.
	/// @param origin the request's `Origin` header.
	/// @return the value or `null` if the origin is not allowed.
	public @Nullable String allowOriginOrNull(@Nullable String origin) {
		if (origin == null) {
			return null;
		}
		if (anyOrigin || origins.contains(origin)) {
			return origin;
		}
		return null;
	}

}
