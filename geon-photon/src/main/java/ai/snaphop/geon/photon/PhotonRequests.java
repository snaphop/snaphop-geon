package ai.snaphop.geon.photon;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.GeoPoint;
import ai.snaphop.geon.Language;
import ai.snaphop.geon.LocationBias;
import ai.snaphop.geon.Place;
import ai.snaphop.geon.PlaceFilter;
import ai.snaphop.geon.QueryOptions;
import ai.snaphop.geon.ReverseQuery;
import ai.snaphop.geon.SearchQuery;
import ai.snaphop.geon.StructuredQuery;
import ai.snaphop.geon.TagFilter;
import ai.snaphop.geon.http.HttpRequest;

/// Parses Photon request parameters into Geon queries.
///
/// Parameters are checked in the same order as Photon checks them, so the first
/// problem reported is the same one Photon would report, with the same message.
final class PhotonRequests {

	private static final String ALLOWED_SEARCH = "[include, location_bias_scale, debug, dedupe, bbox, countrycode, lon, zoom, layer, q, limit, osm_tag, suggest_addresses, exclude, geometry, lang, lat]";

	private static final String ALLOWED_STRUCTURED = "[include, location_bias_scale, debug, dedupe, city, bbox, countrycode, county, postcode, lon, zoom, layer, housenumber, street, district, limit, osm_tag, suggest_addresses, exclude, geometry, state, lang, lat]";

	private static final String ALLOWED_REVERSE = "[include, debug, dedupe, query_string_filter, lon, layer, limit, osm_tag, distance_sort, exclude, geometry, lang, radius, lat]";

	private static final Set<String> BASE = Set.of("lang", "limit", "debug", "dedupe", "geometry", "osm_tag", "layer",
			"include", "exclude");

	private static final Set<String> SEARCH = union(BASE,
			Set.of("lat", "lon", "location_bias_scale", "zoom", "bbox", "suggest_addresses"));

	private static final Set<String> FREE = union(SEARCH, Set.of("q", "countrycode"));

	private static final List<String> STRUCTURED_FIELDS = List.of("countrycode", "state", "county", "city", "postcode",
			"district", "housenumber", "street");

	private static final Set<String> STRUCTURED = union(SEARCH, Set.copyOf(STRUCTURED_FIELDS));

	private static final Set<String> REVERSE = union(BASE,
			Set.of("lat", "lon", "radius", "query_string_filter", "distance_sort"));

	private static final String LABEL = "[a-zA-Z0-9_-]+";

	private static final Pattern CATEGORIES = Pattern
		.compile(LABEL + "(\\." + LABEL + ")+(," + LABEL + "(\\." + LABEL + ")+)*");

	private static final String LAYERS = EnumSet.allOf(AddressType.class)
		.stream()
		.map(AddressType::label)
		.toList()
		.toString();

	/// How a result should be formatted.
	record Format(Language language, boolean geometry, boolean debug) {
	}

	record Search(SearchQuery query, Format format) {
	}

	record Structured(StructuredQuery query, Format format) {
	}

	record Reverse(ReverseQuery query, Format format) {
	}

	private final Set<String> languages;

	private final String languageList;

	private final String defaultLanguage;

	private final int maxResults;

	private final int maxReverseResults;

	private final boolean geometries;

	PhotonRequests(List<String> languages, String defaultLanguage, int maxResults, int maxReverseResults,
			boolean geometries) {
		// Photon keeps the languages in a HashSet; its iteration order shows in an error message.
		this.languages = new java.util.HashSet<>(languages);
		this.languageList = String.join(", ", this.languages);
		this.defaultLanguage = defaultLanguage;
		this.maxResults = maxResults;
		this.maxReverseResults = maxReverseResults;
		this.geometries = geometries;
	}

	Search search(HttpRequest r) {
		checkParams(r, FREE, ALLOWED_SEARCH);
		var base = base(r, maxResults, 15);
		var bias = searchBase(r, base);
		List<String> countries = r.params("countrycode").stream().map(c -> c.toUpperCase(Locale.ROOT)).toList();
		String q = r.param("q");
		if ((q == null || q.isBlank()) && base.include.isEmpty()) {
			throw PhotonError.message(400, "q parameter is required when no include categories are specified");
		}
		var options = base.options(countries);
		return new Search(new SearchQuery(q, bias.bias, bias.suggestAddresses, options), base.format);
	}

	Structured structured(HttpRequest r) {
		checkParams(r, STRUCTURED, ALLOWED_STRUCTURED);
		if (STRUCTURED_FIELDS.stream().noneMatch(f -> r.param(f) != null)) {
			throw PhotonError.message(400, "at least one of the parameters " + STRUCTURED_FIELDS + " is required.");
		}
		var base = base(r, maxResults, 15);
		var bias = searchBase(r, base);
		var query = new StructuredQuery(r.param("countrycode"), r.param("state"), r.param("county"), r.param("city"),
				r.param("postcode"), r.param("district"), r.param("street"), r.param("housenumber"), bias.bias,
				base.options(List.of()));
		return new Structured(query, base.format);
	}

	Reverse reverse(HttpRequest r) {
		checkParams(r, REVERSE, ALLOWED_REVERSE);
		GeoPoint point = latLon(r, true);
		var base = base(r, maxReverseResults, 1);
		Double radius = number(r, "radius");
		if (radius != null && !(radius > 0)) {
			throw PhotonError.invalid("radius", "Invalid value for 'radius' parameter.", PhotonError.number(radius));
		}
		String filter = r.param("query_string_filter");
		var where = filter == null || filter.isEmpty() ? ReverseQuery.ANY : QueryStringFilter.parse(filter);
		boolean sort = bool(r, "distance_sort", true);
		var query = new ReverseQuery(java.util.Objects.requireNonNull(point),
				radius == null ? ReverseQuery.DEFAULT_RADIUS_KM : radius, sort, where, base.options(List.of()));
		return new Reverse(query, base.format);
	}

	/// The parameters every endpoint shares.
	private final class Base {

		Format format = new Format(Language.DEFAULT, false, false);

		int limit;

		boolean dedupe;

		final Set<AddressType> layers = EnumSet.noneOf(AddressType.class);

		final List<TagFilter> tags = new ArrayList<>();

		final List<Set<String>> include = new ArrayList<>();

		final List<Set<String>> exclude = new ArrayList<>();

		@Nullable BBox bbox;

		QueryOptions options(List<String> countries) {
			var filter = new PlaceFilter(new LinkedHashSet<>(countries), layers, tags, include, exclude, bbox);
			return new QueryOptions(format.language(), limit, filter, dedupe, format.debug());
		}

	}

	private Base base(HttpRequest r, int max, int defaultLimit) {
		var b = new Base();
		String language = language(r);
		Integer limit = integer(r, "limit");
		b.limit = limit == null ? Math.min(defaultLimit, max) : Math.clamp(limit, 1, max);
		boolean debug = bool(r, "debug", false);
		b.dedupe = bool(r, "dedupe", true);
		List<String> layers = r.params("layer");
		for (String layer : layers) {
			AddressType type = AddressType.ofLabelOrNull(layer);
			if (type == null || !layer.equals(type.label())) {
				throw PhotonError.invalid("layer", "Unknown layer type. Available layers: " + LAYERS,
						PhotonError.strings(layers));
			}
			b.layers.add(type);
		}
		for (String tag : r.params("osm_tag")) {
			TagFilter f = tagFilterOrNull(tag);
			if (f == null) {
				throw PhotonError.unparsable("osm_tag", tag);
			}
			b.tags.add(f);
		}
		categories(r, "include", b.include);
		categories(r, "exclude", b.exclude);
		boolean geometry = bool(r, "geometry", false);
		if (geometry && !geometries) {
			throw PhotonError.invalid("geometry", "Geometry output requested but not available in database.", "true");
		}
		String fallback = defaultLanguage.equals(language) ? null : defaultLanguage;
		b.format = new Format(new Language(language, fallback), geometry, debug);
		return b;
	}

	private record Bias(@Nullable LocationBias bias, boolean suggestAddresses) {
	}

	private Bias searchBase(HttpRequest r, Base b) {
		Integer zoom = integer(r, "zoom");
		Double scale = number(r, "location_bias_scale");
		GeoPoint point = latLon(r, false);
		b.bbox = bbox(r);
		boolean suggest = bool(r, "suggest_addresses", false);
		LocationBias bias = point == null ? null
				: new LocationBias(point, zoom == null ? LocationBias.DEFAULT_ZOOM : zoom,
						scale == null ? LocationBias.DEFAULT_SCALE : scale);
		return new Bias(bias, suggest);
	}

	private String language(HttpRequest r) {
		String lang = r.param("lang");
		if (lang != null) {
			if (!lang.equals(Place.DEFAULT_NAME) && !languages.contains(lang)) {
				throw PhotonError.invalid("lang", "Language is not supported. Supported are: default, " + languageList,
						PhotonError.string(lang));
			}
			return lang;
		}
		String header = r.header("Accept-Language");
		if (header != null && !header.isBlank()) {
			try {
				String match = Locale.lookupTag(Locale.LanguageRange.parse(header), languages);
				if (match != null) {
					return match;
				}
			}
			catch (IllegalArgumentException e) {
				// A malformed header is ignored, as in Photon.
			}
		}
		return defaultLanguage;
	}

	private static void categories(HttpRequest r, String param, List<Set<String>> out) {
		List<String> values = r.params(param);
		for (String v : values) {
			if (!CATEGORIES.matcher(v).matches()) {
				throw PhotonError.invalid(param, "Invalid category pattern in '" + param + "'.",
						PhotonError.strings(values));
			}
		}
		for (String v : values) {
			out.add(new LinkedHashSet<>(List.of(v.split(","))));
		}
	}

	private static @Nullable GeoPoint latLon(HttpRequest r, boolean mandatory) {
		Double lat = number(r, "lat");
		if (lat != null && !(lat >= -90 && lat <= 90)) {
			throw PhotonError.invalid("lat", "Invalid value for 'lat' parameter.", PhotonError.number(lat));
		}
		if (mandatory && lat == null) {
			throw PhotonError.invalid("lat", "Missing parameter 'lat'.", "null");
		}
		if (!mandatory && lat != null && r.param("lon") == null) {
			throw PhotonError.invalid("lat", "Missing parameter 'lon'.", PhotonError.number(lat));
		}
		Double lon = number(r, "lon");
		if (lon != null && !(lon >= -180 && lon <= 180)) {
			throw PhotonError.invalid("lon", "Invalid value for 'lon' parameter.", PhotonError.number(lon));
		}
		if (mandatory && lon == null) {
			throw PhotonError.invalid("lon", "Missing parameter 'lon'.", "null");
		}
		if (!mandatory && lon != null && lat == null) {
			throw PhotonError.invalid("lon", "Missing parameter 'lat'.", PhotonError.number(lon));
		}
		return lat == null || lon == null ? null : new GeoPoint(lon, lat);
	}

	private static @Nullable BBox bbox(HttpRequest r) {
		String raw = r.param("bbox");
		if (raw == null) {
			return null;
		}
		String[] parts = raw.split(",");
		if (parts.length != 4) {
			throw PhotonError.unparsable("bbox", raw);
		}
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) {
			double limit = i % 2 == 0 ? 180 : 90;
			try {
				v[i] = Double.parseDouble(parts[i]);
			}
			catch (NumberFormatException e) {
				throw PhotonError.unparsable("bbox", raw);
			}
			if (!(v[i] >= -limit && v[i] <= limit)) {
				throw PhotonError.unparsable("bbox", raw);
			}
		}
		return new BBox(v[0], v[1], v[2], v[3]);
	}

	/// Parses Photon's `osm_tag` syntax: `key`, `key:value`, `:value`, each optionally
	/// negated with `!`.
	static @Nullable TagFilter tagFilterOrNull(String filter) {
		String[] parts = filter.split(":");
		if (parts.length == 2) {
			boolean excludeKey = parts[0].startsWith("!");
			boolean excludeValue = parts[1].startsWith("!");
			String key = (excludeKey ? parts[0].substring(1) : parts[0]).trim();
			String value = (excludeValue ? parts[1].substring(1) : parts[1]).trim();
			if (value.isEmpty()) {
				return null;
			}
			if (!key.isEmpty() && !excludeKey && excludeValue) {
				return new TagFilter(TagFilter.Kind.EXCLUDE_VALUE, key, value);
			}
			return new TagFilter(excludeKey || excludeValue ? TagFilter.Kind.EXCLUDE : TagFilter.Kind.INCLUDE,
					key.isEmpty() ? null : key, value);
		}
		if (parts.length == 1 && parts[0].equals(filter)) {
			boolean exclude = filter.startsWith("!");
			String key = exclude ? filter.substring(1) : filter;
			if (key.isEmpty()) {
				return null;
			}
			return new TagFilter(exclude ? TagFilter.Kind.EXCLUDE : TagFilter.Kind.INCLUDE, key, null);
		}
		return null;
	}

	private static void checkParams(HttpRequest r, Set<String> allowed, String allowedText) {
		for (String name : r.query().keySet()) {
			if (!allowed.contains(name)) {
				throw PhotonError.message(400,
						"Unknown query parameter '" + name + "'.  Allowed parameters are: " + allowedText);
			}
		}
	}

	private static @Nullable Integer integer(HttpRequest r, String name) {
		String raw = r.param(name);
		if (raw == null) {
			return null;
		}
		try {
			return Integer.valueOf(raw);
		}
		catch (NumberFormatException e) {
			throw PhotonError.unparsable(name, raw);
		}
	}

	private static @Nullable Double number(HttpRequest r, String name) {
		String raw = r.param(name);
		if (raw == null) {
			return null;
		}
		try {
			double d = Double.parseDouble(raw);
			if (Double.isNaN(d)) {
				throw new NumberFormatException();
			}
			return d;
		}
		catch (NumberFormatException e) {
			throw PhotonError.unparsable(name, raw);
		}
	}

	/// Photon reads `1`, `yes` and `true` in any case as true and anything else as false.
	private static boolean bool(HttpRequest r, String name, boolean absent) {
		String raw = r.param(name);
		if (raw == null) {
			return absent;
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		return lower.equals("1") || lower.equals("yes") || lower.equals("true");
	}

	private static Set<String> union(Set<String> a, Set<String> b) {
		var out = new java.util.HashSet<>(a);
		out.addAll(b);
		return Set.copyOf(out);
	}

}
