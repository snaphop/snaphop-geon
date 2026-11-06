package ai.snaphop.geon.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// Runs a real Photon: imports a fixture dump and serves it on a free port.
///
/// The Photon jar comes from the `photon.jar` system property, or is downloaded once
/// from Photon's GitHub release to `~/.cache/geon/`.
final class PhotonProcess implements AutoCloseable {

	static final String VERSION = "1.3.0";

	private final Process process;

	private final int port;

	private final HttpClient client = HttpClient.newHttpClient();

	private PhotonProcess(Process process, int port) {
		this.process = process;
		this.port = port;
	}

	static PhotonProcess start(String fixture, Path dir) throws Exception {
		Path jar = jar();
		Path dump = dir.resolve(fixture + ".jsonl");
		try (InputStream in = Objects.requireNonNull(
				PhotonProcess.class.getResourceAsStream("/fixtures/" + fixture + ".jsonl"), fixture)) {
			Files.copy(in, dump, StandardCopyOption.REPLACE_EXISTING);
		}
		Path data = dir.resolve("photon");
		run(List.of(java(), "-jar", jar.toString(), "import", "-import-file", dump.toString(), "-data-dir",
				data.toString(), "-extra-tags", "ALL"), dir.resolve("photon-import.log"), Duration.ofMinutes(5));
		int port;
		try (var socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		var process = new ProcessBuilder(java(), "-jar", jar.toString(), "serve", "-data-dir", data.toString(),
				"-listen-port", Integer.toString(port), "-cors-origin", "example.com")
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("photon-serve.log").toFile())
			.start();
		var photon = new PhotonProcess(process, port);
		long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
		while (System.nanoTime() < deadline) {
			if (!process.isAlive()) {
				throw new IllegalStateException("Photon exited; see " + dir.resolve("photon-serve.log"));
			}
			try {
				if (photon.get("/status").status() == 200) {
					return photon;
				}
			}
			catch (IOException e) {
				// not listening yet
			}
			Thread.sleep(200);
		}
		photon.close();
		throw new IllegalStateException("Photon did not start within 2 minutes; see " + dir.resolve("photon-serve.log"));
	}

	Fixture.Response get(String pathAndQuery) throws IOException, InterruptedException {
		var request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery.replace(" ", "%20")))
			.timeout(Duration.ofSeconds(30))
			.build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofString());
		return new Fixture.Response(response.statusCode(), response.body());
	}

	private static String java() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private static void run(List<String> command, Path log, Duration timeout) throws Exception {
		var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("Timed out: " + command + "; see " + log);
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException("Failed with " + process.exitValue() + ": " + command + "; see " + log);
		}
	}

	private static Path jar() throws IOException, InterruptedException {
		String configured = System.getProperty("photon.jar");
		if (configured != null) {
			return Path.of(configured);
		}
		Path jar = Path.of(System.getProperty("user.home"), ".cache", "geon", "photon-" + VERSION + ".jar");
		if (Files.isRegularFile(jar)) {
			return jar;
		}
		Files.createDirectories(jar.getParent());
		Path partial = jar.resolveSibling(jar.getFileName() + ".part");
		var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
		var request = HttpRequest
			.newBuilder(URI.create(
					"https://github.com/komoot/photon/releases/download/" + VERSION + "/photon-" + VERSION + ".jar"))
			.build();
		var response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial));
		if (response.statusCode() != 200) {
			throw new IOException("Downloading Photon " + VERSION + " failed with HTTP " + response.statusCode());
		}
		Files.move(partial, jar, StandardCopyOption.ATOMIC_MOVE);
		return jar;
	}

	@Override
	public void close() {
		process.destroy();
		try {
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
		catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
		client.close();
	}

}
