package ai.snaphop.geon.index;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.GeoPoint;
import ai.snaphop.geon.Language;
import ai.snaphop.geon.LocationBias;
import ai.snaphop.geon.Place;

/// The ranking formula. Every number that shapes ranking lives here.
///
/// ```
/// score = 0.75 * relevance + 0.25 * standing + bonuses
///
/// relevance = 0.55 * text + 0.45 * completeness
/// text      = sum(token length * match quality * field weight) / sum(token length)
/// completeness = share of the result's own name (or street and house number)
///                that the query covers
/// standing  = importance                                   without location bias
///           = scale * importance + (1 - scale) * proximity with location bias
/// ```
///
/// * Match quality is 1 for an exact term, [#PREFIX_QUALITY] for a prefix and
///   [#FUZZY_QUALITY] for a term within an edit distance of 1 or 2.
/// * Field weights prefer a match in the place's own name over a match in its
///   address, see [#fieldWeight(int)].
/// * Proximity follows Photon's curve: close to 1 within the bias radius, then halving
///   every [LocationBias#decayKm()].
/// * Bonuses: [#TARGET_BONUS] when a structured search's most specific part names
///   the result's own address level, and [#ADDRESS_BONUS] for addresses when the
///   query asked to suggest addresses.
final class Scoring {

	static final double PREFIX_QUALITY = 0.8;

	static final double[] FUZZY_QUALITY = { 1.0, 0.7, 0.5 };

	static final double TARGET_BONUS = 0.1;

	static final double ADDRESS_BONUS = 0.1;

	private final Language language;

	private final @Nullable LocationBias bias;

	private final @Nullable AddressType target;

	private final boolean suggestAddresses;

	private final boolean explain;

	Scoring(Language language, @Nullable LocationBias bias, @Nullable AddressType target, boolean suggestAddresses,
			boolean explain) {
		this.language = language;
		this.bias = bias != null && bias.isActive() ? bias : null;
		this.target = target;
		this.suggestAddresses = suggestAddresses;
		this.explain = explain;
	}

	/// The field ordinal with the highest weight among the bits of `mask`.
	static int bestField(int mask) {
		int best = Field.CONTEXT.ordinal();
		double weight = -1;
		for (Field f : Field.values()) {
			if ((mask & f.bit) != 0 && fieldWeight(f.ordinal()) > weight) {
				weight = fieldWeight(f.ordinal());
				best = f.ordinal();
			}
		}
		return best;
	}

	/// How much a match in a field counts, by field ordinal.
	static double fieldWeight(int field) {
		return switch (Field.values()[field]) {
			case NAME, HOUSENUMBER -> 1.0;
			case STREET -> 0.9;
			case POSTCODE -> 0.85;
			case LOCALITY, DISTRICT, CITY -> 0.8;
			case COUNTY, STATE, COUNTRY -> 0.7;
			case CONTEXT -> 0.6;
			case COUNTRYCODE -> 0.4;
			case CATEGORY -> 0.5;
		};
	}

	static double text(Searcher.TokenMatch[] matches) {
		double total = 0;
		double matched = 0;
		for (Searcher.TokenMatch m : matches) {
			if (m != null) {
				matched += m.token().length * m.quality();
			}
		}
		for (Searcher.TokenMatch m : matches) {
			total += m == null ? 1 : m.token().length;
		}
		return total == 0 ? 0 : matched / total;
	}

	double preliminary(Searcher.Candidate c, DocCodec.Reader docs) {
		double standing = standing(docs.importance(c.doc), docs.lon(c.doc), docs.lat(c.doc));
		boolean hasText = c.matches.length > 0;
		return hasText ? 0.75 * c.text + 0.25 * standing : standing;
	}

	void rescore(Searcher.Candidate c) {
		Place p = java.util.Objects.requireNonNull(c.place);
		double standing = standing(p.importance(), p.centroid().lon(), p.centroid().lat());
		if (c.matches.length == 0) {
			c.score = standing;
			if (explain) {
				c.explanation = String.format(Locale.ROOT, "standing=%.4f%s", standing, biasNote(p));
			}
			return;
		}
		double completeness = completeness(p, c.matches);
		double relevance = 0.55 * c.text + 0.45 * completeness;
		double bonus = 0;
		if (target != null && p.addressType() == target) {
			bonus += TARGET_BONUS;
		}
		if (suggestAddresses && p.houseNumber() != null && p.names().isEmpty()) {
			bonus += ADDRESS_BONUS;
		}
		c.score = 0.75 * relevance + 0.25 * standing + bonus;
		if (explain) {
			var sb = new StringBuilder();
			sb.append(String.format(Locale.ROOT, "score=%.4f relevance=%.4f (text=%.4f completeness=%.4f)"
					+ " standing=%.4f bonus=%.2f%s matches=[", c.score, relevance, c.text, completeness, standing, bonus,
					biasNote(p)));
			for (int i = 0; i < c.matches.length; i++) {
				var m = c.matches[i];
				if (i > 0) {
					sb.append(", ");
				}
				if (m == null) {
					sb.append("?");
				}
				else {
					sb.append(m.token().text).append("->")
						.append(Field.values()[m.field()].name().toLowerCase(Locale.ROOT))
						.append(String.format(Locale.ROOT, ":%.2f", m.quality()));
				}
			}
			c.explanation = sb.append(']').toString();
		}
	}

	private String biasNote(Place p) {
		if (bias == null) {
			return "";
		}
		double km = p.centroid().distanceKm(bias.point());
		return String.format(Locale.ROOT, " distance=%.3fkm proximity=%.4f", km, proximity(km));
	}

	private double standing(double importance, double lon, double lat) {
		if (bias == null) {
			return importance;
		}
		double km = GeoPoint.distanceKm(lon, lat, bias.point().lon(), bias.point().lat());
		return bias.scale() * importance + (1 - bias.scale()) * proximity(km);
	}

	private double proximity(double km) {
		LocationBias b = java.util.Objects.requireNonNull(bias);
		double radius = b.radiusKm();
		if (km < radius) {
			return 1.0 - 0.1 * km / radius;
		}
		return 0.9 * Math.exp(Math.log(0.5) * (km - radius) / b.decayKm());
	}

	/// The share of the result's identifying words that the query covers. A query for
	/// `berlin` covers all of `Berlin` but only half of `Berlin Hauptbahnhof`.
	private double completeness(Place p, Searcher.TokenMatch[] matches) {
		String name = p.displayNameOrNull(language.preferred(), language.fallback());
		List<String> identity;
		if (name != null) {
			identity = Text.tokens(name, false);
		}
		else {
			identity = new java.util.ArrayList<>();
			String street = p.addressNameOrNull(AddressType.STREET, language.preferred(), language.fallback());
			if (street != null) {
				identity.addAll(Text.tokens(street, false));
			}
			if (p.houseNumber() != null) {
				identity.addAll(Text.tokens(p.houseNumber(), false));
			}
		}
		if (identity.isEmpty()) {
			return 0;
		}
		int covered = 0;
		for (String word : identity) {
			for (Searcher.TokenMatch m : matches) {
				if (m != null && covers(m.token(), word)) {
					covered++;
					break;
				}
			}
		}
		return (double) covered / identity.size();
	}

	private static boolean covers(Searcher.Token t, String word) {
		if (word.equals(t.text) || (t.prefix && word.startsWith(t.text))) {
			return true;
		}
		return t.fuzzyIds.length > 0 && EditDistance.bounded(t.text, word, 2) <= (t.length <= 5 ? 1 : 2);
	}

}
