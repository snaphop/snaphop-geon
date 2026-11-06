package ai.snaphop.geon.photon;

import java.lang.System.Logger.Level;
import java.time.Instant;

import ai.snaphop.geon.GeonIndex;
import ai.snaphop.geon.IndexInfo;
import ai.snaphop.geon.http.HttpRequest;
import ai.snaphop.geon.http.HttpResponse;
import ai.snaphop.geon.http.HttpServer;

/// Serves Photon's HTTP API from a [GeonIndex].
///
/// | Endpoint | Purpose |
/// |---|---|
/// | `/api` | free text search |
/// | `/structured` | search by address parts |
/// | `/reverse` | places near a coordinate |
/// | `/status` | health check and data date |
///
/// Parameters, error messages and responses follow Photon 1.3. Deliberate
/// differences:
///
/// * `debug=1` returns Geon's score explanations instead of OpenSearch internals.
/// * `/reverse` without `lat` reports a missing parameter rather than an internal
///   error, and rejects a radius that is not positive.
/// * `query_string_filter` supports a documented subset of the Lucene syntax, see
///   [QueryStringFilter].
///
/// ```java
/// var server = PhotonApi.builder(index).build()
///     .routes(HttpServer.builder().port(2322))
///     .start();
/// ```
public final class PhotonApi {

	private static final System.Logger logger = System.getLogger(PhotonApi.class.getName());

	private final GeonIndex index;

	private final PhotonRequests requests;

	private final String version;

	private final String gitCommit;

	private PhotonApi(Builder b) {
		this.index = b.index;
		IndexInfo info = b.index.info();
		this.requests = new PhotonRequests(info.languages(), b.defaultLanguage, b.maxResults, b.maxReverseResults,
				info.hasGeometries());
		this.version = b.version;
		this.gitCommit = b.gitCommit;
	}

	/// Starts configuring the API.
	/// @param index the index to serve.
	/// @return a builder.
	public static Builder builder(GeonIndex index) {
		return new Builder(index);
	}

	/// Adds the API's routes, its not found response and its error handling to a server.
	/// @param server the server builder.
	/// @return the same server builder.
	public HttpServer.Builder routes(HttpServer.Builder server) {
		return server.route("/api", r -> respond(() -> {
			var s = requests.search(r);
			return GeoJson.write(index.search(s.query()), s.format());
		}))
			.route("/structured", r -> respond(() -> {
				var s = requests.structured(r);
				return GeoJson.write(index.search(s.query()), s.format());
			}))
			.route("/reverse", r -> respond(() -> {
				var s = requests.reverse(r);
				return GeoJson.write(index.reverse(s.query()), s.format());
			}))
			.route("/status", this::status)
			.notFound(PhotonApi::notFound)
			.errorHandler(PhotonApi::error);
	}

	@FunctionalInterface
	private interface Body {

		byte[] get();

	}

	private static HttpResponse respond(Body body) {
		try {
			return HttpResponse.json(200, body.get());
		}
		catch (PhotonError e) {
			return HttpResponse.json(e.status(), e.body());
		}
	}

	private HttpResponse status(HttpRequest r) {
		Instant date = index.info().dataTimestamp();
		var w = PhotonError.JSON.bufferedWriter();
		w.beginObject();
		w.name("status");
		w.value("Ok");
		w.name("import_date");
		w.value(date == null ? "" : date.toString());
		w.name("version");
		w.value(version);
		w.name("git_commit");
		w.value(gitCommit);
		w.endObject();
		return HttpResponse.json(200, w.result());
	}

	/// The body follows the shape of Photon's web framework, as an RFC 9457 problem.
	private static HttpResponse notFound(HttpRequest r) {
		var w = PhotonError.JSON.bufferedWriter();
		w.beginObject();
		w.name("title");
		w.value("Endpoint " + r.method() + " " + r.path() + " not found");
		w.name("status");
		w.value(404);
		w.name("type");
		w.value("about:blank");
		w.name("details");
		w.beginObject();
		w.endObject();
		w.endObject();
		return HttpResponse.json(404, w.result());
	}

	private static HttpResponse error(HttpRequest r, Throwable e) {
		if (e instanceof IllegalArgumentException) {
			// Invalid combinations the core model rejects; Photon reports these as 400 too.
			return HttpResponse.json(400, PhotonError.message(400, String.valueOf(e.getMessage())).body());
		}
		logger.log(Level.ERROR, "Request " + r.path() + " " + r.query() + " failed", e);
		return HttpResponse.json(500, PhotonError.message(500, "Internal server error.").body());
	}

	/// Configures a [PhotonApi].
	public static final class Builder {

		private final GeonIndex index;

		private String defaultLanguage = "default";

		private int maxResults = 50;

		private int maxReverseResults = 50;

		private String version = "unknown";

		private String gitCommit = "unknown";

		private Builder(GeonIndex index) {
			this.index = index;
		}

		/// The language used when a request names none and its `Accept-Language` header
		/// matches none of the index's languages. Defaults to `default`, the local names.
		/// This is Photon's `-default-language`.
		/// @param language a language code or `default`.
		/// @return this.
		public Builder defaultLanguage(String language) {
			this.defaultLanguage = language;
			return this;
		}

		/// The most results `/api` and `/structured` return. Defaults to 50, as in Photon.
		/// @param max the maximum.
		/// @return this.
		public Builder maxResults(int max) {
			this.maxResults = max;
			return this;
		}

		/// The most results `/reverse` returns. Defaults to 50, as in Photon.
		/// @param max the maximum.
		/// @return this.
		public Builder maxReverseResults(int max) {
			this.maxReverseResults = max;
			return this;
		}

		/// The version and commit `/status` reports.
		/// @param version the version.
		/// @param gitCommit the commit.
		/// @return this.
		public Builder version(String version, String gitCommit) {
			this.version = version;
			this.gitCommit = gitCommit;
			return this;
		}

		/// Builds the API.
		/// @return the API.
		public PhotonApi build() {
			return new PhotonApi(this);
		}

	}

}
