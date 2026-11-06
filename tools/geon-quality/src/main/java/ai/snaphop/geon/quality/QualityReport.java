package ai.snaphop.geon.quality;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.Place;
import ai.snaphop.geon.dump.DumpReader;
import io.avaje.json.mapper.JsonMapper;

/// Measures how often Photon compatible geocoders find places sampled from a dump.
///
/// Each sampled place becomes queries a person might type, and the report says how
/// often the place comes back first (top 1), among the first five (top 5), and the mean
/// reciprocal rank (MRR):
///
/// | Kind | Query |
/// |---|---|
/// | `address` | `Main Street 12, Springfield` for a house number without a name |
/// | `name+city` | `Café Zürich, Springfield` |
/// | `prefix` | the first 60% of the name, as typed so far |
/// | `typo+city` | the name with two neighbouring letters swapped, then the city |
///
/// For named places a result with the same name, OSM key and city also counts: long
/// streets and rivers are split into several OSM objects that are all right answers.
///
/// ```
/// zstd -dc photon-dump-switzerland.jsonl.zst | java -cp ... ai.snaphop.geon.quality.QualityReport \
///     --dump - --endpoint photon=http://localhost:2322 --endpoint geon=http://localhost:2323
/// ```
///
/// Options: `--rate` the share of places to sample (default 0.004), `--cases` the most
/// cases (default 1200), `--seed` the random seed (default 5). The same seed and dump
/// give the same cases, so endpoints and versions can be compared.
public final class QualityReport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private QualityReport() {
	}

	/// A query and what counts as finding it.
	record Case(String kind, String query, String osmType, long osmId, @Nullable String sameAs) {
	}

	/// Runs the report.
	/// @param args see the class documentation.
	/// @throws Exception on failure.
	public static void main(String[] args) throws Exception {
		String dump = null;
		var endpoints = new LinkedHashMap<String, String>();
		double rate = 0.004;
		int maxCases = 1200;
		long seed = 5;
		for (int i = 0; i < args.length; i++) {
			String value = i + 1 < args.length ? args[i + 1] : "";
			switch (args[i]) {
				case "--dump" -> dump = value;
				case "--endpoint" -> {
					int eq = value.indexOf('=');
					endpoints.put(eq > 0 ? value.substring(0, eq) : value, eq > 0 ? value.substring(eq + 1) : value);
				}
				case "--rate" -> rate = Double.parseDouble(value);
				case "--cases" -> maxCases = Integer.parseInt(value);
				case "--seed" -> seed = Long.parseLong(value);
				default -> throw new IllegalArgumentException("Unknown option " + args[i]
						+ ". Use --dump FILE|-, --endpoint NAME=URL (repeatable), --rate, --cases, --seed.");
			}
			i++;
		}
		if (dump == null || endpoints.isEmpty()) {
			throw new IllegalArgumentException("Need --dump and at least one --endpoint.");
		}
		List<Case> cases = sample(dump, rate, maxCases, seed);
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			for (var e : endpoints.entrySet()) {
				report(e.getKey(), e.getValue(), cases, client);
			}
		}
	}

	static List<Case> sample(String dump, double rate, int maxCases, long seed) throws IOException {
		var random = new Random(seed);
		var cases = new ArrayList<Case>();
		try (InputStream in = dump.equals("-") ? new BufferedInputStream(System.in, 1 << 16)
				: new BufferedInputStream(Files.newInputStream(Path.of(dump)), 1 << 16)) {
			DumpReader.builder().build().read(in, p -> {
				if (random.nextDouble() <= rate) {
					addCases(p, random, cases);
				}
			});
		}
		Collections.shuffle(cases, random);
		return cases.size() > maxCases ? cases.subList(0, maxCases) : cases;
	}

	private static void addCases(Place p, Random random, List<Case> out) {
		String city = p.addressNameOrNull(AddressType.CITY, Place.DEFAULT_NAME, null);
		String name = p.names().get(Place.DEFAULT_NAME);
		String osmType = Objects.requireNonNullElse(p.osmType(), "");
		if (city == null) {
			return;
		}
		String street = p.addressNameOrNull(AddressType.STREET, Place.DEFAULT_NAME, null);
		if (p.houseNumber() != null && street != null && name == null) {
			out.add(new Case("address", street + " " + p.houseNumber() + ", " + city, osmType, p.osmId(), null));
		}
		if (name != null && name.codePointCount(0, name.length()) > 3) {
			String same = key(name, p.osmKey(), city);
			out.add(new Case("name+city", name + ", " + city, osmType, p.osmId(), same));
			int[] cps = name.codePoints().toArray();
			int prefix = Math.max(3, (int) (cps.length * 0.6));
			out.add(new Case("prefix", new String(cps, 0, prefix), osmType, p.osmId(), same));
			if (cps.length > 5) {
				int i = 1 + random.nextInt(cps.length - 2);
				int t = cps[i];
				cps[i] = cps[i - 1];
				cps[i - 1] = t;
				out.add(new Case("typo+city", new String(cps, 0, cps.length) + ", " + city, osmType, p.osmId(), same));
			}
		}
	}

	private static String key(@Nullable Object name, @Nullable Object osmKey, @Nullable Object city) {
		return name + "\u0000" + osmKey + "\u0000" + city;
	}

	@SuppressWarnings("unchecked")
	private static int rank(HttpClient client, String base, Case c) throws IOException, InterruptedException {
		String url = base + "/api?limit=10&q=" + URLEncoder.encode(c.query(), StandardCharsets.UTF_8);
		var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			return 0;
		}
		var features = (List<Map<String, Object>>) JSON.fromJsonObject(response.body()).get("features");
		for (int i = 0; i < features.size(); i++) {
			var p = (Map<String, Object>) features.get(i).get("properties");
			Object id = p.get("osm_id");
			boolean sameObject = c.osmType().equals(p.get("osm_type")) && id instanceof Number n
					&& n.longValue() == c.osmId();
			boolean sameNamed = c.sameAs() != null
					&& c.sameAs().equals(key(p.get("name"), p.get("osm_key"), p.get("city")));
			if (sameObject || sameNamed) {
				return i + 1;
			}
		}
		return 0;
	}

	private static void report(String name, String base, List<Case> cases, HttpClient client) throws Exception {
		// kind -> count, top1, top5, reciprocal rank sum
		var stats = new TreeMap<String, double[]>();
		for (Case c : cases) {
			int r = rank(client, base, c);
			double[] s = stats.computeIfAbsent(c.kind(), k -> new double[4]);
			s[0]++;
			s[1] += r == 1 ? 1 : 0;
			s[2] += r >= 1 && r <= 5 ? 1 : 0;
			s[3] += r >= 1 ? 1.0 / r : 0;
		}
		for (var e : stats.entrySet()) {
			double[] s = e.getValue();
			System.out.println(String.format(Locale.ROOT, "%-8s %-10s n=%5d  top1=%6.1f%%  top5=%6.1f%%  MRR=%.3f", name,
					e.getKey(), (int) s[0], 100 * s[1] / s[0], 100 * s[2] / s[0], s[3] / s[0]));
		}
	}

}
