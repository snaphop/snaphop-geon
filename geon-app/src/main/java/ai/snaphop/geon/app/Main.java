package ai.snaphop.geon.app;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.BBox;
import ai.snaphop.geon.GeoPoint;
import ai.snaphop.geon.GeonVersion;
import ai.snaphop.geon.IndexInfo;
import ai.snaphop.geon.Synonyms;
import ai.snaphop.geon.dump.DumpReader;
import ai.snaphop.geon.dump.ExtraTags;
import ai.snaphop.geon.http.Cors;
import ai.snaphop.geon.http.HttpResponse;
import ai.snaphop.geon.http.HttpServer;
import ai.snaphop.geon.http.Metrics;
import ai.snaphop.geon.index.FileIndex;
import ai.snaphop.geon.index.IndexWriter;
import ai.snaphop.geon.logging.GeonLogging;
import ai.snaphop.geon.photon.PhotonApi;
import ai.snaphop.geon.photon.PhotonSynonyms;

/// The `geon` command.
///
/// ```
/// geon import -import-file photon-dump.jsonl [options]
/// geon serve [options]
/// geon version
/// ```
///
/// Option names follow Photon's, so Photon start scripts work with few changes. Every
/// option can also come from an environment variable, `-listen-port` from
/// `GEON_LISTEN_PORT` for example; see [Options]. Logging is configured with
/// `GEON_LOG_*` variables, see `geon-logging`.
///
/// The index is the single file `geon.idx` in the data directory, or the file given
/// with `-index`.
public final class Main {

	private static final System.Logger logger = System.getLogger(Main.class.getName());

	private static final String USAGE = """
			Usage: geon <command> [options]

			Commands:
			  import   Build an index from a Nominatim dump file (JSON lines).
			  serve    Serve the Photon compatible API from an index.
			  version  Print the version.

			Every option can also be set with an environment variable: -listen-port is
			GEON_LISTEN_PORT, -cors-any is GEON_CORS_ANY=true. The command line wins.
			Logging is set with GEON_LOG_* variables (GEON_LOG_level=debug).

			Common options:
			  -data-dir DIR        Directory holding the index (default: current directory).
			  -index FILE          The index file (default: DIR/geon.idx).

			Import options:
			  -import-file FILE    The dump to import; '-' reads standard input. Files ending
			                       in .gz are decompressed. For .zst dumps use
			                       'zstd -dc dump.jsonl.zst | geon import -import-file -'.
			  -languages LANG,...  Name languages to keep (default: en,de,fr,it).
			  -extra-tags TAGS     ALL, or a comma separated list of extra tags to keep.
			  -full-geometries     Keep full geometries for 'geometry=1' responses.
			  -country-codes CC,.. Only import these countries.
			  -import-bbox BOX     Only import places inside minLon,minLat,maxLon,maxLat, for
			                       example New England from the USA dump:
			                       -73.8,40.9,-66.8,47.5
			  -import-memory SIZE  Memory for collecting postings before writing them to disk,
			                       for example 512m or 4g. Default: a quarter of the heap.
			  -tmp-dir DIR         Directory for temporary import files (default: the index
			                       directory). They can be larger than the finished index.

			Serve options:
			  -listen-ip IP        Address of the API (default: 127.0.0.1).
			  -listen-port PORT    Port of the API (default: 2322).
			  -admin-ip IP         Address of the admin port (default: 127.0.0.1). Keep it
			                       private: it serves /metrics, /health and /info.
			  -admin-port PORT     Admin port (default: 2323), or none.
			  -cors-any            Allow cross origin requests from any origin.
			  -cors-origin O,...   Allow cross origin requests from these origins.
			  -default-language L  Language when a request names none (default: default).
			  -max-results N       Most results for /api and /structured (default: 50).
			  -max-reverse-results N  Most results for /reverse (default: 50).
			  -synonym-file FILE   Synonyms and classification terms in Photon's JSON format.
			  -metrics-enable prometheus  Also serve /metrics on the API port, as Photon does.
			""";

	private static final Set<String> IGNORED = Set.of("cluster", "transport-addresses", "j", "query-timeout");

	private static final Set<String> IMPORT_SWITCHES = Set.of("full-geometries");

	private static final Set<String> IMPORT_OPTIONS = Set.of("data-dir", "index", "import-file", "languages",
			"extra-tags", "country-codes", "import-bbox", "import-memory", "tmp-dir");

	private static final Set<String> SERVE_SWITCHES = Set.of("cors-any");

	private static final Set<String> SERVE_OPTIONS = Set.of("data-dir", "index", "listen-ip", "listen-port",
			"admin-ip", "admin-port", "cors-origin", "default-language", "max-results", "max-reverse-results",
			"synonym-file", "metrics-enable");

	/// `GEON_*` variables that are not options: logging and the JVM launcher's options.
	private static final Set<String> OTHER_VARIABLES = Set.of("GEON_JAVA_OPTS");

	private Main() {
	}

	/// Runs the command line and exits with status 0 on success, 1 on a usage error
	/// and 2 on any other failure.
	/// @param args the arguments.
	public static void main(String[] args) {
		GeonLogging.start();
		int status = run(List.of(args), System.getenv(), System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
		if (args.isEmpty() || args.getFirst().equals("-h") || args.getFirst().equals("--help")
				|| args.getFirst().equals("help")) {
			out.print(USAGE);
			return 0;
		}
		var rest = args.subList(1, args.size());
		var warnings = new ArrayList<String>();
		try {
			unknownVariables(env).forEach(v -> warnings.add("Ignoring environment variable " + v
					+ ": no geon option has that name. Options are GEON_ and the option name in upper case."));
			switch (args.getFirst()) {
				case "import" -> runImport(
						Options.parse(rest, IMPORT_SWITCHES, IMPORT_OPTIONS, IGNORED, env, warnings), warnings, out);
				case "serve" -> serve(Options.parse(rest, SERVE_SWITCHES, SERVE_OPTIONS, IGNORED, env, warnings),
						warnings, out);
				case "version" -> out.println("geon " + GeonVersion.VERSION + " (" + GeonVersion.COMMIT + ")");
				default -> throw new Options.UsageException(
						"Unknown command '" + args.getFirst() + "'. Use import, serve or version.");
			}
			return 0;
		}
		catch (Options.UsageException e) {
			err.println("geon: " + e.getMessage());
			err.println("Run 'geon --help' for usage.");
			return 1;
		}
		catch (Exception e) {
			err.println("geon: " + e.getMessage());
			logger.log(Level.DEBUG, "Command failed", e);
			return 2;
		}
	}

	/// `GEON_*` variables that no command reads, which are likely typos. One env file
	/// usually serves both commands, so only names unknown to all of them count.
	static Set<String> unknownVariables(Map<String, String> env) {
		var known = new TreeSet<String>(OTHER_VARIABLES);
		for (var names : List.of(IMPORT_SWITCHES, IMPORT_OPTIONS, SERVE_SWITCHES, SERVE_OPTIONS)) {
			names.forEach(n -> known.add(Options.variable(n)));
		}
		return env.keySet()
			.stream()
			.filter(v -> v.startsWith(Options.ENV_PREFIX) && !v.startsWith("GEON_LOG_") && !known.contains(v))
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Path indexFile(Options o) {
		String index = o.valueOrNull("index");
		return index != null ? Path.of(index) : Path.of(o.value("data-dir", ".")).resolve("geon.idx");
	}

	private static void runImport(Options o, List<String> warnings, PrintStream out) throws Exception {
		warnings.forEach(w -> logger.log(Level.WARNING, w));
		String source = o.valueOrNull("import-file");
		if (source == null) {
			if (o.positional().size() != 1) {
				throw new Options.UsageException("import needs -import-file FILE, or '-import-file -' for standard input.");
			}
			source = o.positional().getFirst();
		}
		List<String> languages = o.has("languages") ? o.list("languages") : DumpReader.DEFAULT_LANGUAGES;
		var reader = DumpReader.builder()
			.languages(languages)
			.extraTags(o.has("extra-tags") ? ExtraTags.parse(o.value("extra-tags", "")) : ExtraTags.NONE)
			.geometries(o.has("full-geometries"))
			.countryCodes(o.list("country-codes"))
			.bbox(bbox(o))
			.build();
		Path index = indexFile(o);
		long start = System.nanoTime();
		try (InputStream in = open(source)) {
			var builder = IndexWriter.builder(index)
				.languages(languages)
				.geometries(o.has("full-geometries"))
				.memoryBudget(memory(o));
			String tmp = o.valueOrNull("tmp-dir");
			if (tmp != null) {
				builder.tempDirectory(Path.of(tmp));
			}
			try (var writer = builder.build()) {
				var summary = reader.read(in, writer::add);
				writer.dataTimestamp(summary.dataTimestamp());
				var info = writer.finish();
				out.printf("Imported %,d places (%,d skipped) into %s (%,d bytes) in %.1f s.%n", info.placeCount(),
						summary.skippedCount(), index, Files.size(index), (System.nanoTime() - start) / 1e9);
			}
		}
	}

	/// Parses `-import-bbox`: `minLon,minLat,maxLon,maxLat`.
	/// @return the box or `null`.
	static @Nullable BBox bbox(Options o) throws Options.UsageException {
		String value = o.valueOrNull("import-bbox");
		if (value == null) {
			return null;
		}
		String[] parts = value.split(",");
		try {
			if (parts.length != 4) {
				throw new IllegalArgumentException();
			}
			double[] v = new double[4];
			for (int i = 0; i < 4; i++) {
				v[i] = Double.parseDouble(parts[i].strip());
			}
			new GeoPoint(v[0], v[1]);
			new GeoPoint(v[2], v[3]);
			return new BBox(v[0], v[1], v[2], v[3]);
		}
		catch (IllegalArgumentException e) {
			throw new Options.UsageException("Invalid box '" + value + "' for " + o.source("import-bbox")
					+ ". Use minLon,minLat,maxLon,maxLat in degrees, for example -73.8,40.9,-66.8,47.5.");
		}
	}

	/// Parses `-import-memory`, a size such as `512m` or `4g`.
	/// @return bytes, or 0 for the default.
	static long memory(Options o) throws Options.UsageException {
		String value = o.valueOrNull("import-memory");
		if (value == null) {
			return 0;
		}
		String v = value.toLowerCase(java.util.Locale.ROOT);
		long unit = switch (v.charAt(v.length() - 1)) {
			case 'k' -> 1L << 10;
			case 'm' -> 1L << 20;
			case 'g' -> 1L << 30;
			default -> 1;
		};
		String digits = unit == 1 ? v : v.substring(0, v.length() - 1);
		try {
			long n = Long.parseLong(digits);
			if (n <= 0) {
				throw new NumberFormatException();
			}
			return n * unit;
		}
		catch (NumberFormatException e) {
			throw new Options.UsageException("Invalid memory size '" + value + "' for " + o.source("import-memory")
					+ ". Use a number of bytes with an optional k, m or g suffix, for example 512m.");
		}
	}

	private static InputStream open(String source) throws IOException, Options.UsageException {
		if (source.equals("-")) {
			return new BufferedInputStream(System.in, 1 << 16);
		}
		Path path = Path.of(source);
		if (!Files.isReadable(path)) {
			throw new Options.UsageException("Cannot read the dump file " + path.toAbsolutePath() + ".");
		}
		String name = path.getFileName().toString();
		if (name.endsWith(".zst") || name.endsWith(".bz2")) {
			throw new Options.UsageException("Geon reads uncompressed or .gz dumps. Decompress " + name
					+ " on the fly, for example: zstd -dc " + name + " | geon import -import-file -");
		}
		InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 16);
		return name.endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;
	}

	private static void serve(Options o, List<String> warnings, PrintStream out) throws Exception {
		warnings.forEach(w -> logger.log(Level.WARNING, w));
		Path file = indexFile(o);
		if (!Files.isReadable(file)) {
			throw new Options.UsageException("No index at " + file.toAbsolutePath()
					+ ". Run 'geon import' first or point -data-dir or -index at an existing index.");
		}
		long start = System.nanoTime();
		Synonyms synonyms = Synonyms.NONE;
		String synonymFile = o.valueOrNull("synonym-file");
		if (synonymFile != null) {
			Path path = Path.of(synonymFile);
			if (!Files.isReadable(path)) {
				throw new Options.UsageException("Cannot read the synonym file " + path.toAbsolutePath() + ".");
			}
			synonyms = PhotonSynonyms.load(path);
		}
		String photonMetrics = o.valueOrNull("metrics-enable");
		if (photonMetrics != null && !photonMetrics.equals("prometheus")) {
			throw new Options.UsageException("Unsupported " + o.source("metrics-enable") + " value '" + photonMetrics
					+ "'. The only supported value is prometheus.");
		}
		Integer adminPort = adminPort(o);
		var index = FileIndex.open(file, synonyms);
		IndexInfo info = index.info();
		var metrics = new Metrics().gauge("geon_index_places", "Places in the index.", info::placeCount);
		var cors = o.has("cors-any") ? Cors.ANY : new Cors(false, o.list("cors-origin"));
		var api = PhotonApi.builder(index)
			.defaultLanguage(o.value("default-language", "default"))
			.maxResults(o.integer("max-results", 50))
			.maxReverseResults(o.integer("max-reverse-results", 50))
			.version(GeonVersion.VERSION, GeonVersion.COMMIT)
			.build();
		var http = HttpServer.builder()
			.host(o.value("listen-ip", "127.0.0.1"))
			.port(o.integer("listen-port", 2322))
			.cors(cors)
			.metrics(metrics);
		if (photonMetrics != null) {
			http.route("/metrics", metrics.handler());
		}
		var server = api.routes(http).start();
		HttpServer admin = null;
		if (adminPort != null) {
			admin = HttpServer.builder()
				.host(o.value("admin-ip", "127.0.0.1"))
				.port(adminPort)
				.route("/metrics", metrics.handler())
				.route("/health", req -> HttpResponse.json(200, "{\"status\":\"Ok\"}"))
				.route("/info", req -> HttpResponse.json(200, info(info)))
				.start();
		}
		HttpServer adminServer = admin;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (adminServer != null) {
				adminServer.close();
			}
			server.close();
			index.close();
		}, "geon-shutdown"));
		out.printf("Serving %s (%,d places) on http://%s:%d/%s (started in %.0f ms)%n", file, info.placeCount(),
				o.value("listen-ip", "127.0.0.1"), server.port(),
				admin == null ? "" : ", admin on http://" + o.value("admin-ip", "127.0.0.1") + ":" + admin.port() + "/",
				(System.nanoTime() - start) / 1e6);
		out.flush();
		server.awaitClose();
	}

	/// The admin port, or `null` for `none`.
	private static @Nullable Integer adminPort(Options o) throws Options.UsageException {
		String value = o.value("admin-port", "2323");
		if (value.equals("none")) {
			return null;
		}
		return o.integer("admin-port", 2323);
	}

	private static String info(IndexInfo info) {
		var sb = new StringBuilder("{\"version\":\"").append(GeonVersion.VERSION)
			.append("\",\"commit\":\"")
			.append(GeonVersion.COMMIT)
			.append("\",\"places\":")
			.append(info.placeCount())
			.append(",\"data_timestamp\":")
			.append(info.dataTimestamp() == null ? "null" : "\"" + info.dataTimestamp() + "\"")
			.append(",\"languages\":[");
		for (int i = 0; i < info.languages().size(); i++) {
			sb.append(i == 0 ? "\"" : ",\"").append(info.languages().get(i)).append('"');
		}
		return sb.append("]}").toString();
	}

}
