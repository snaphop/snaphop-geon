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
/// relevance = 0.45 * text + 0.4 * completeness + 0.15 * lead
/// text      = sum(token length * match quality * field weight) / sum(token length)
/// completeness = share of the result's own name (or street and house number)
///                that the query covers; a prefix covers only the typed share of
///                a word
/// standing  = importance                                   without location bias
///           = scale * importance + (1 - scale) * proximity with location bias
/// ```
///
/// * Match quality is 1 for an exact term, [#PREFIX_QUALITY] for a prefix and
///   [#FUZZY_QUALITY] for a term within an edit distance of 1 or 2.
/// * Field weights prefer a match in the place's own name over a match in its
///   address, see [#fieldWeight(int)].
/// * Lead is the share of the query (its first comma separated part, if any) that
///   matches the result's own name or house number, see [#lead].
/// * Proximity follows Photon's curve: close to 1 within the bias radius, then halving
///   every [LocationBias#decayKm()].
/// * Bonuses: [#TARGET_BONUS] when a structured search's most specific part names
///   the result's own address level, [#HOUSENUMBER_BONUS] when a query word is
///   exactly the result's house number, and [#ADDRESS_BONUS] for addresses when the
///   query asked to suggest addresses.
final class Scoring {

	static final double PREFIX_QUALITY = 0.8;

	static final double[] FUZZY_QUALITY = { 1.0, 0.7, 0.5 };

	/// A word that names the place's type through a classification term, such as
	/// `station` for a railway station.
	static final double CLASSIFICATION_QUALITY = 0.8;

	static final double TARGET_BONUS = 0.1;

	static final double ADDRESS_BONUS = 0.1;

	static final double HOUSENUMBER_BONUS = 0.1;

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

	/// How much a match in a field counts, by field ordinal. For an address (a place
	/// with a house number) the street identifies the place as much as a name does.
	static double fieldWeight(int field, boolean address) {
		return address && field == Field.STREET.ordinal() ? 1.0 : fieldWeight(field);
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

	static double text(TokenMatch[] matches) {
		double total = 0;
		double matched = 0;
		for (TokenMatch m : matches) {
			if (m != null) {
				matched += m.token().length * m.quality();
			}
		}
		for (TokenMatch m : matches) {
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
		double relevance = 0.45 * c.text + 0.4 * completeness + 0.15 * lead(p, c.matches);
		double bonus = 0;
		if (target != null && p.addressType() == target) {
			bonus += TARGET_BONUS;
		}
		if (suggestAddresses && p.houseNumber() != null && p.names().isEmpty()) {
			bonus += ADDRESS_BONUS;
		}
		for (TokenMatch m : c.matches) {
			if (m != null && m.field() == Field.HOUSENUMBER.ordinal() && m.quality() == 1.0) {
				bonus += HOUSENUMBER_BONUS;
				break;
			}
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

	/// How much of the query names the result itself. With commas only the first part
	/// counts: people type "name, city", so in `Vordere Schwendistrasse, Wildhaus` the
	/// street wins over a map board named Wildhaus that stands on it. Without commas all
	/// words count, so `Fussweg z` prefers "Fussweg zur Badi" over a "Fussweg" in Zürich.
	/// @return the share of those words' letters matched in the result's name or house
	///   number (or street, for an address).
	private static double lead(Place p, TokenMatch[] matches) {
		int total = 0;
		int named = 0;
		for (TokenMatch m : matches) {
			if (m == null || m.token().segment != 0) {
				continue;
			}
			total += m.token().length;
			int f = m.field();
			boolean identity = f == Field.NAME.ordinal() || f == Field.HOUSENUMBER.ordinal()
					|| (f == Field.STREET.ordinal() && p.houseNumber() != null && p.names().isEmpty());
			if (identity) {
				named += m.token().length;
			}
		}
		return total == 0 ? 0 : (double) named / total;
	}

	/// The share of the result's identifying words that the query covers. A query for
	/// `berlin` covers all of `Berlin` but only half of `Berlin Hauptbahnhof`.
	private double completeness(Place p, TokenMatch[] matches) {
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
		double covered = 0;
		for (String word : identity) {
			double best = 0;
			for (TokenMatch m : matches) {
				if (m != null) {
					best = Math.max(best, coverage(m.token(), word));
				}
			}
			covered += best;
		}
		return covered / identity.size();
	}

	/// How much of a word a query token covers: all of it when equal, the typed share
	/// when it is a prefix (`de` covers a third of `denner`), and most of it for a typo.
	private static double coverage(Token t, String word) {
		if (word.equals(t.text) || t.synonymTexts.contains(word)) {
			return 1.0;
		}
		if (t.prefix && word.startsWith(t.text)) {
			return (double) t.length / word.codePointCount(0, word.length());
		}
		if (t.fuzzyIds.length > 0 && EditDistance.bounded(t.text, word, 2) <= (t.length <= 5 ? 1 : 2)) {
			return 0.8;
		}
		return 0;
	}

}
