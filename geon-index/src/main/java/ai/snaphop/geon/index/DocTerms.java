package ai.snaphop.geon.index;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.Place;

/// Extracts the terms of a place and the fields each occurs in.
///
/// Terms come in two groups that are stored separately:
///
/// * a place's own terms ([#own(Place)]): names, house number and categories;
/// * its address terms ([#address(Map, Set, String, String)]): address parts, context
///   names, postcode and country code, which are shared by every place with the same
///   address and stored once per address set.
final class DocTerms {

	private DocTerms() {
	}

	/// The place's own terms.
	/// @return term to [Field] mask.
	static Map<String, Integer> own(Place p) {
		var terms = new HashMap<String, Integer>();
		p.names().values().forEach(n -> add(terms, n, Field.NAME));
		String hnr = p.houseNumber();
		if (hnr != null) {
			add(terms, hnr, Field.HOUSENUMBER);
			addJoined(terms, hnr, Field.HOUSENUMBER);
		}
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

	/// The terms of an address set.
	/// @return term to [Field] mask.
	static Map<String, Integer> address(Map<AddressType, Map<String, String>> parts, Set<String> context,
			@Nullable String postcode, @Nullable String countryCode) {
		var terms = new HashMap<String, Integer>();
		parts.forEach((type, names) -> {
			Field f = Field.of(type);
			names.values().forEach(n -> add(terms, n, f));
		});
		context.forEach(c -> add(terms, c, Field.CONTEXT));
		if (postcode != null) {
			add(terms, postcode, Field.POSTCODE);
			addJoined(terms, postcode, Field.POSTCODE);
		}
		if (countryCode != null) {
			terms.merge(countryCode.toLowerCase(Locale.ROOT), Field.COUNTRYCODE.bit, (a, b) -> a | b);
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
