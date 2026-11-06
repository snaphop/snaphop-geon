package ai.snaphop.geon.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.Synonyms;
import ai.snaphop.geon.dump.DumpReader;
import ai.snaphop.geon.dump.ExtraTags;
import ai.snaphop.geon.http.Cors;
import ai.snaphop.geon.http.HttpServer;
import ai.snaphop.geon.http.Metrics;
import ai.snaphop.geon.index.FileIndex;
import ai.snaphop.geon.index.IndexWriter;
import ai.snaphop.geon.photon.PhotonApi;

/// Imports a dump from the test resources and serves it on a free port, the same way
/// `geon import` and `geon serve` do.
final class Fixture implements AutoCloseable {

	private final FileIndex index;

	private final HttpServer server;

	private final HttpClient client = HttpClient.newHttpClient();

	private Fixture(FileIndex index, HttpServer server) {
		this.index = index;
		this.server = server;
	}

	/// Imports `/fixtures/{name}.jsonl` with all extra tags and starts the Photon API.
	static Fixture start(String name, Path dir) throws Exception {
		return start(name, dir, 0);
	}

	/// Like [#start(String, Path)] with an import memory budget in bytes; 0 for the default.
	static Fixture start(String name, Path dir, long memoryBudget) throws Exception {
		return start(name, dir, memoryBudget, Synonyms.NONE);
	}

	/// Like [#start(String, Path, long)] with query time synonyms.
	static Fixture start(String name, Path dir, long memoryBudget, Synonyms synonyms) throws Exception {
		Path file = dir.resolve(name + ".geon");
		var reader = DumpReader.builder().extraTags(ExtraTags.ALL).build();
		try (InputStream in = Objects.requireNonNull(Fixture.class.getResourceAsStream("/fixtures/" + name + ".jsonl"),
				name);
				var writer = IndexWriter.builder(file)
					.languages(DumpReader.DEFAULT_LANGUAGES)
					.memoryBudget(memoryBudget)
					.build()) {
			var summary = reader.read(in, writer::add);
			writer.dataTimestamp(summary.dataTimestamp());
			writer.finish();
		}
		var index = FileIndex.open(file, synonyms);
		var metrics = new Metrics().gauge("geon_index_places", "Places in the index.", () -> index.info().placeCount());
		var server = PhotonApi.builder(index)
			.version("test", "test")
			.build()
			.routes(HttpServer.builder()
				.port(0)
				.cors(new Cors(false, java.util.List.of("example.com")))
				.metrics(metrics)
				.route("/metrics", metrics.handler()))
			.start();
		return new Fixture(index, server);
	}

	record Response(int status, String body) {
		@Override
		public String toString() {
			return status + " " + body;
		}
	}

	Response get(String pathAndQuery) {
		return get(pathAndQuery, null, null);
	}

	Response get(String pathAndQuery, @Nullable String header, @Nullable String value) {
		try {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery.replace(" ", "%20")));
			if (header != null && value != null) {
				request.header(header, value);
			}
			var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			return new Response(response.statusCode(), response.body());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void close() {
		server.close();
		index.close();
		client.close();
	}

}
