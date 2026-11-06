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
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;

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
import ai.snaphop.geon.photon.PhotonSynonyms;
import io.jstach.rainbowgum.RainbowGum;

/// The `geon` command.
///
/// ```
/// geon import -import-file photon-dump.jsonl [-data-dir DIR] [-languages en,de,fr,it]
///             [-extra-tags ALL|tag,...] [-full-geometries] [-country-codes CC,...]
/// geon serve  [-data-dir DIR] [-listen-ip IP] [-listen-port 2322] [-cors-any]
///             [-cors-origin ORIGIN,...] [-default-language LANG]
///             [-max-results 50] [-max-reverse-results 50]
/// ```
///
/// Option names follow Photon's, so Photon start scripts work with few changes. The
/// index is the single file `geon.idx` in the data directory, or the file given
/// with `-index`.
public final class Main {

	private static final System.Logger logger = System.getLogger(Main.class.getName());

	private static final String USAGE = """
			Usage: geon <command> [options]

			Commands:
			  import   Build an index from a Nominatim dump file (JSON lines).
			  serve    Serve the Photon compatible API from an index.
			  version  Print the version.

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
			  -import-memory SIZE  Memory for collecting postings before writing them to disk,
			                       for example 512m or 4g. Default: a quarter of the heap.
			                       Environment variable: GEON_IMPORT_MEMORY.
			  -tmp-dir DIR         Directory for temporary import files (default: the index
			                       directory). They can be larger than the finished index.

			Serve options:
			  -listen-ip IP        Address to listen on (default: 127.0.0.1).
			  -listen-port PORT    Port to listen on (default: 2322).
			  -cors-any            Allow cross origin requests from any origin.
			  -cors-origin O,...   Allow cross origin requests from these origins.
			  -default-language L  Language when a request names none (default: default).
			  -max-results N       Most results for /api and /structured (default: 50).
			  -max-reverse-results N  Most results for /reverse (default: 50).
			  -synonym-file FILE   Synonyms and classification terms in Photon's JSON format.
			  -metrics-enable prometheus  Serve request and index metrics at /metrics.
			""";

	private static final Set<String> IGNORED = Set.of("cluster", "transport-addresses", "j", "query-timeout");

	private Main() {
	}

	/// Runs the command line and exits with status 0 on success, 1 on a usage error
	/// and 2 on any other failure.
	/// @param args the arguments.
	public static void main(String[] args) {
		// Start logging explicitly: the native image is built with
		// logging.systemlogger.initialize=false (see doc/dependency-issues.md).
		RainbowGum.of();
		int status = run(List.of(args), System.out, System.err);
		if (status != 0) {
			System.exit(status);
		}
	}

	static int run(List<String> args, PrintStream out, PrintStream err) {
		if (args.isEmpty() || args.getFirst().equals("-h") || args.getFirst().equals("--help")
				|| args.getFirst().equals("help")) {
			out.print(USAGE);
			return 0;
		}
		var rest = args.subList(1, args.size());
		var warnings = new ArrayList<String>();
		try {
			switch (args.getFirst()) {
				case "import" -> runImport(Options.parse(rest, Set.of("full-geometries"),
						Set.of("data-dir", "index", "import-file", "languages", "extra-tags", "country-codes",
								"import-memory", "tmp-dir"),
						IGNORED,
						warnings), warnings, out);
				case "serve" -> serve(Options.parse(rest, Set.of("cors-any"),
						Set.of("data-dir", "index", "listen-ip", "listen-port", "cors-origin", "default-language",
								"max-results", "max-reverse-results", "synonym-file", "metrics-enable"),
						IGNORED, warnings), warnings, out);
				case "version" -> out.println("geon " + version());
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
			.build();
		Path index = indexFile(o);
		long start = System.nanoTime();
		try (InputStream in = open(source)) {
			var builder = IndexWriter.builder(index)
				.languages(languages)
				.geometries(o.has("full-geometries"))
				.memoryBudget(memory(o.valueOrNull("import-memory"), System.getenv("GEON_IMPORT_MEMORY")));
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

	/// Parses a size such as `512m` or `4g` from the option, else the environment variable.
	/// @return bytes, or 0 for the default.
	static long memory(@Nullable String option, @Nullable String env) throws Options.UsageException {
		String value = option != null ? option : env;
		if (value == null || value.isBlank()) {
			return 0;
		}
		String v = value.strip().toLowerCase(java.util.Locale.ROOT);
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
			throw new Options.UsageException("Invalid memory size '" + value + "'"
					+ (option != null ? " for -import-memory" : " in GEON_IMPORT_MEMORY")
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
		String synonymFile = o.valueOrNull("synonym-file");
		Synonyms synonyms = Synonyms.NONE;
		if (synonymFile != null) {
			Path path = Path.of(synonymFile);
			if (!Files.isReadable(path)) {
				throw new Options.UsageException("Cannot read the synonym file " + path.toAbsolutePath() + ".");
			}
			synonyms = PhotonSynonyms.load(path);
		}
		var index = FileIndex.open(file, synonyms);
		var cors = o.has("cors-any") ? Cors.ANY : new Cors(false, o.list("cors-origin"));
		var api = PhotonApi.builder(index)
			.defaultLanguage(o.value("default-language", "default"))
			.maxResults(o.integer("max-results", 50))
			.maxReverseResults(o.integer("max-reverse-results", 50))
			.version(version(), System.getProperty("geon.gitCommit", "unknown"))
			.build();
		var http = HttpServer.builder()
			.host(o.value("listen-ip", "127.0.0.1"))
			.port(o.integer("listen-port", 2322))
			.cors(cors);
		String metrics = o.valueOrNull("metrics-enable");
		if (metrics != null) {
			if (!metrics.equals("prometheus")) {
				throw new Options.UsageException(
						"Unsupported -metrics-enable value '" + metrics + "'. The only supported value is prometheus.");
			}
			long places = index.info().placeCount();
			http.metrics("/metrics", new Metrics().gauge("geon_index_places", "Places in the index.", () -> places));
		}
		var server = api.routes(http).start();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.close();
			index.close();
		}, "geon-shutdown"));
		out.printf("Serving %s (%,d places) on http://%s:%d/ (started in %.0f ms)%n", file, index.info().placeCount(),
				o.value("listen-ip", "127.0.0.1"), server.port(), (System.nanoTime() - start) / 1e6);
		server.awaitClose();
	}

	static String version() {
		var props = new Properties();
		try (InputStream in = Main.class.getResourceAsStream("version.properties")) {
			if (in != null) {
				props.load(in);
			}
		}
		catch (IOException e) {
			// fall through to unknown
		}
		return props.getProperty("version", "unknown");
	}

}
