package ai.snaphop.geon.photon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.Place;

/// Parses the subset of Lucene query syntax that Photon's `/reverse` accepts in
/// `query_string_filter`.
///
/// Photon passes this parameter straight to OpenSearch. Geon supports:
///
/// * `field:value` and `field:"quoted value"`; a value ending in `*` matches by prefix
/// * `AND`, `OR`, `NOT`, a leading `-` for not, and parentheses
/// * terms separated only by spaces, which must all match
///
/// Fields:
///
/// * exact: `osm_key`, `osm_value`, `osm_type`, `osm_id`, `type`, `countrycode`,
///   `postcode`, `housenumber`, `categories`
/// * words, case insensitive: `name`, `street`, `locality`, `district`, `city`,
///   `county`, `state`, `country`, each optionally with a language such as
///   `name.en` or `name.default`
///
/// Anything else is rejected with a message naming the problem, rather than ignored.
final class QueryStringFilter {

	private final String input;

	private final List<String> tokens;

	private int pos;

	private QueryStringFilter(String input) {
		this.input = input;
		this.tokens = tokenize(input);
	}

	static Predicate<Place> parse(String input) {
		var parser = new QueryStringFilter(input);
		Predicate<Place> p = parser.or();
		if (parser.pos < parser.tokens.size()) {
			throw parser.error("unexpected '" + parser.tokens.get(parser.pos) + "'");
		}
		return p;
	}

	private PhotonError error(String problem) {
		return PhotonError.invalid("query_string_filter", "Unsupported query_string_filter: " + problem
				+ ". Supported: field:value terms with AND, OR, NOT, - and parentheses.", PhotonError.string(input));
	}

	private static List<String> tokenize(String s) {
		var out = new ArrayList<String>();
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			}
			else if (c == '(' || c == ')') {
				out.add(String.valueOf(c));
				i++;
			}
			else {
				var sb = new StringBuilder();
				while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '(' && s.charAt(i) != ')') {
					if (s.charAt(i) == '"') {
						int end = s.indexOf('"', i + 1);
						end = end < 0 ? s.length() : end;
						sb.append(s, i, Math.min(end + 1, s.length()));
						i = end + 1;
					}
					else {
						sb.append(s.charAt(i++));
					}
				}
				out.add(sb.toString());
			}
		}
		return out;
	}

	private boolean peek(String token) {
		return pos < tokens.size() && tokens.get(pos).equals(token);
	}

	private Predicate<Place> or() {
		Predicate<Place> left = and();
		while (peek("OR") || peek("||")) {
			pos++;
			left = left.or(and());
		}
		return left;
	}

	private Predicate<Place> and() {
		Predicate<Place> left = unary();
		while (pos < tokens.size() && !peek(")") && !peek("OR") && !peek("||")) {
			if (peek("AND") || peek("&&")) {
				pos++;
			}
			left = left.and(unary());
		}
		return left;
	}

	private Predicate<Place> unary() {
		if (peek("NOT") || peek("!")) {
			pos++;
			return unary().negate();
		}
		if (pos >= tokens.size()) {
			throw error("expression ends early");
		}
		String token = tokens.get(pos++);
		if (token.equals("(")) {
			Predicate<Place> inner = or();
			if (!peek(")")) {
				throw error("missing ')'");
			}
			pos++;
			return inner;
		}
		if (token.startsWith("-") && token.length() > 1) {
			return term(token.substring(1)).negate();
		}
		if (token.startsWith("+") && token.length() > 1) {
			return term(token.substring(1));
		}
		return term(token);
	}

	private Predicate<Place> term(String token) {
		int colon = token.indexOf(':');
		if (colon <= 0 || colon == token.length() - 1) {
			throw error("'" + token + "' is not a field:value term");
		}
		String field = token.substring(0, colon);
		String value = token.substring(colon + 1);
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		boolean prefix = value.endsWith("*");
		if (prefix) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.contains("*") || value.contains("?") || value.contains("~") || value.contains("^")) {
			throw error("wildcards, fuzzy and boost operators are not supported in '" + token + "'");
		}
		String v = value;
		Predicate<String> exact = s -> s != null && (prefix ? s.startsWith(v) : s.equals(v));
		return switch (field) {
			case "osm_key" -> p -> exact.test(p.osmKey());
			case "osm_value" -> p -> exact.test(p.osmValue());
			case "osm_type" -> p -> exact.test(p.osmType());
			case "osm_id" -> p -> exact.test(Long.toString(p.osmId()));
			case "type" -> p -> exact.test(p.addressType().label());
			case "countrycode" -> p -> exact.test(p.countryCode());
			case "postcode" -> p -> exact.test(p.postcode());
			case "housenumber" -> p -> exact.test(p.houseNumber());
			case "categories" -> p -> p.categories().stream().anyMatch(exact);
			default -> textField(field, v, prefix, token);
		};
	}

	private Predicate<Place> textField(String field, String value, boolean prefix, String token) {
		int dot = field.indexOf('.');
		String base = dot < 0 ? field : field.substring(0, dot);
		String language = dot < 0 ? null : field.substring(dot + 1);
		AddressType type = base.equals("name") ? null : AddressType.ofLabelOrNull(base);
		if (!base.equals("name") && (type == null || type == AddressType.HOUSE || type == AddressType.OTHER)) {
			throw error("unknown field '" + field + "' in '" + token + "'");
		}
		String wanted = value.toLowerCase(Locale.ROOT);
		return p -> {
			Map<String, String> names = type == null ? p.names() : p.address().getOrDefault(type, Map.of());
			for (var e : names.entrySet()) {
				if (language != null && !language.equals(e.getKey())) {
					continue;
				}
				String name = e.getValue().toLowerCase(Locale.ROOT);
				if (wanted.indexOf(' ') >= 0 && name.contains(wanted)) {
					return true;
				}
				for (String word : name.split("[\\s,;]+")) {
					if (prefix ? word.startsWith(wanted) : word.equals(wanted)) {
						return true;
					}
				}
			}
			return false;
		};
	}

}
