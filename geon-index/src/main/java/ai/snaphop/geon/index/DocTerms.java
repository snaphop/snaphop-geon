package ai.snaphop.geon.index;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ai.snaphop.geon.Place;

/// Extracts the terms of a place and the fields each occurs in.
final class DocTerms {

	private DocTerms() {
	}

	/// The terms of a place.
	/// @return term to [Field] mask.
	static Map<String, Integer> of(Place p) {
		var terms = new HashMap<String, Integer>();
		p.names().values().forEach(n -> add(terms, n, Field.NAME));
		String hnr = p.houseNumber();
		if (hnr != null) {
			add(terms, hnr, Field.HOUSENUMBER);
			addJoined(terms, hnr, Field.HOUSENUMBER);
		}
		p.address().forEach((type, names) -> {
			Field f = Field.of(type);
			names.values().forEach(n -> add(terms, n, f));
		});
		String postcode = p.postcode();
		if (postcode != null) {
			add(terms, postcode, Field.POSTCODE);
			addJoined(terms, postcode, Field.POSTCODE);
		}
		String cc = p.countryCode();
		if (cc != null) {
			terms.merge(cc.toLowerCase(Locale.ROOT), Field.COUNTRYCODE.bit, (a, b) -> a | b);
		}
		p.context().forEach(c -> add(terms, c, Field.CONTEXT));
		for (String category : p.categories()) {
			// Index every level with at least two labels: osm.amenity, osm.amenity.cafe.
			int dot = category.indexOf('.');
			while (dot >= 0) {
				int next = category.indexOf('.', dot + 1);
				String level = next < 0 ? category : category.substring(0, next);
				terms.merge(Field.CATEGORY_PREFIX + level, Field.CATEGORY.bit, (a, b) -> a | b);
				dot = next;
			}
		}
		return terms;
	}

	/// Whether a term in these fields identifies the place itself rather than where it is.
	///
	/// Primary postings are searched first, so a search for `berlin` finds places
	/// named Berlin before the million addresses that are merely in Berlin.
	static boolean isPrimary(int mask, boolean hasHouseNumber) {
		if ((mask & (Field.NAME.bit | Field.HOUSENUMBER.bit)) != 0) {
			return true;
		}
		// An address is identified by its street and number.
		return hasHouseNumber && (mask & Field.STREET.bit) != 0;
	}

	private static void add(Map<String, Integer> terms, String text, Field field) {
		Text.tokens(Text.fold(text), true, t -> terms.merge(t, field.bit, (a, b) -> a | b));
	}

	/// Also indexes the value with all separators removed, so `12 a` is found by `12a`
	/// and `AD 500` by `ad500`.
	private static void addJoined(Map<String, Integer> terms, String text, Field field) {
		var joined = new StringBuilder();
		String folded = Text.fold(text);
		folded.codePoints().filter(Character::isLetterOrDigit).forEach(joined::appendCodePoint);
		if (!joined.isEmpty()) {
			terms.merge(joined.toString(), field.bit, (a, b) -> a | b);
		}
	}

}
