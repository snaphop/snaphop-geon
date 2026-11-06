package ai.snaphop.geon.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.Hit;
import ai.snaphop.geon.LocationBias;
import ai.snaphop.geon.Place;
import ai.snaphop.geon.PlaceFilter;
import ai.snaphop.geon.QueryOptions;
import ai.snaphop.geon.ReverseQuery;
import ai.snaphop.geon.SearchQuery;
import ai.snaphop.geon.StructuredQuery;

/// Runs queries against the index sections. Thread safe; all per query state lives
/// in local objects.
///
/// ## Forward search
///
/// 1. The text is folded and split into tokens. The last token is a prefix unless the
///    text ends with a space.
/// 2. **Strict pass.** The token with the fewest postings drives: its docs are read,
///    primary postings first, and each doc is checked against every other token
///    through the forward index. Because doc IDs follow importance, the most
///    important matches are found first and reading can stop early.
/// 3. **Lenient pass**, only when the strict pass found too few results. Tokens may
///    also match with typos, and long queries may leave a token unmatched.
/// 4. Matches are scored (see [Scoring]) and the best are decoded into places.
final class Searcher {

	/// Stop verifying candidates after this many matches in a pass.
	private static final int MATCH_TARGET = 400;

	/// Read at most this many doc IDs from postings in a pass.
	private static final int CANDIDATE_CAP = 200_000;

	/// Only this many of the best preliminary matches are decoded for final scoring.
	private static final int RESCORE_COUNT = 120;

	/// In the lenient pass, words with more postings than this (or 1% of all places)
	/// do not generate candidates.
	private static final int LENIENT_DRIVER_CAP = 20_000;

	/// Do not expand a prefix over more terms than this; use the prefix list instead.
	private static final int PREFIX_TERM_CAP = 4096;

	private final DocCodec.Reader docs;

	private final Terms terms;

	private final Forward forward;

	private final PackedRTree.Reader tree;

	Searcher(DocCodec.Reader docs, Terms terms, Forward forward, PackedRTree.Reader tree) {
		this.docs = docs;
		this.terms = terms;
		this.forward = forward;
		this.tree = tree;
	}

	/// A query token and the term IDs it can match.
	static final class Token {

		final String text;

		final int length;

		final boolean prefix;

		final int allowed;

		int exactId = -1;

		int prefixFrom;

		int prefixTo;

		/// ID of the capped prefix list term for short prefixes, or -1.
		int prefixListId = -1;

		int[] fuzzyIds = new int[0];

		int[] fuzzyDistances = new int[0];

		Token(String text, boolean prefix, int allowed) {
			this.text = text;
			this.length = text.codePointCount(0, text.length());
			this.prefix = prefix;
			this.allowed = allowed;
		}

		boolean hasStrictTerms() {
			return exactId >= 0 || (prefix && prefixTo > prefixFrom);
		}

	}

	/// How a token matched a doc.
	record TokenMatch(Token token, double quality, int field) {
	}

	/// A doc that passed verification, with its preliminary score.
	static final class Candidate {

		final int doc;

		final TokenMatch[] matches;

		double text;

		double score;

		@Nullable Place place;

		@Nullable String explanation;

		Candidate(int doc, TokenMatch[] matches) {
			this.doc = doc;
			this.matches = matches;
		}

	}

	// ---------------------------------------------------------------- free text

	List<Hit> search(SearchQuery q) {
		QueryOptions o = q.options();
		var filter = new Filter(o.filter());
		String text = q.text();
		if (text == null) {
			return byFilterOnly(filter, q.bias(), o);
		}
		boolean lastIsPrefix = !text.endsWith(" ");
		var tokens = new ArrayList<Token>();
		var raw = Text.tokens(text, false);
		for (int i = 0; i < raw.size(); i++) {
			tokens.add(new Token(raw.get(i), lastIsPrefix && i == raw.size() - 1, Field.TEXT_MASK));
		}
		if (tokens.isEmpty()) {
			return List.of();
		}
		var scoring = new Scoring(o.language(), q.bias(), null, q.suggestAddresses(), o.explain());
		return run(tokens, filter, scoring, o);
	}

	// ---------------------------------------------------------------- structured

	List<Hit> search(StructuredQuery q) {
		QueryOptions o = q.options();
		PlaceFilter base = o.filter();
		Set<String> countries = base.countryCodes();
		if (q.countryCode() != null) {
			countries = Set.of(q.countryCode().toUpperCase(Locale.ROOT));
		}
		var filter = new Filter(new PlaceFilter(countries, base.layers(), base.tagFilters(), base.includeCategories(),
				base.excludeCategories(), base.bbox()));
		// The most specific part names the result; broader parts must match its address.
		record Part(@Nullable String value, int field, AddressType type) {
		}
		var parts = List.of(new Part(q.houseNumber(), Field.HOUSENUMBER.bit, AddressType.HOUSE),
				new Part(q.street(), Field.STREET.bit, AddressType.STREET),
				new Part(q.district(), Field.DISTRICT.bit | Field.LOCALITY.bit, AddressType.DISTRICT),
				new Part(q.postcode(), Field.POSTCODE.bit, AddressType.OTHER),
				new Part(q.city(), Field.CITY.bit, AddressType.CITY),
				new Part(q.county(), Field.COUNTY.bit, AddressType.COUNTY),
				new Part(q.state(), Field.STATE.bit, AddressType.STATE));
		var tokens = new ArrayList<Token>();
		AddressType target = null;
		for (Part part : parts) {
			if (part.value == null) {
				continue;
			}
			int allowed = part.field | Field.CONTEXT.bit;
			if (part.type == AddressType.STREET && q.houseNumber() != null) {
				// Lets the search fall back to the street itself when the house is unknown.
				allowed |= Field.NAME.bit;
			}
			if (target == null) {
				target = part.type;
				if (part.type != AddressType.HOUSE) {
					allowed |= Field.NAME.bit;
				}
			}
			for (String t : Text.tokens(part.value, false)) {
				tokens.add(new Token(t, false, allowed));
			}
		}
		if (tokens.isEmpty()) {
			// Only a country code: return the country itself.
			if (q.countryCode() == null) {
				return List.of();
			}
			var countryFilter = new Filter(new PlaceFilter(countries, Set.of(AddressType.COUNTRY), List.of(), List.of(),
					List.of(), null));
			return byFilterOnly(countryFilter, q.bias(), o);
		}
		var scoring = new Scoring(o.language(), q.bias(), target, false, o.explain());
		return run(tokens, filter, scoring, o);
	}

	// ---------------------------------------------------------------- shared engine

	private List<Hit> run(List<Token> tokens, Filter filter, Scoring scoring, QueryOptions o) {
		for (Token t : tokens) {
			resolve(t);
		}
		var candidates = new ArrayList<Candidate>();
		if (tokens.stream().allMatch(Token::hasStrictTerms)) {
			collect(tokens, filter, 0, candidates);
		}
		if (candidates.isEmpty()) {
			// Like Photon, only relax the query when the strict pass found nothing:
			// otherwise weaker partial matches would pad out good results.
			for (Token t : tokens) {
				resolveFuzzy(t);
			}
			int n = tokens.size();
			int missing = n <= 2 ? 0 : n <= 6 ? 1 : 2;
			collect(tokens, filter, missing, candidates);
		}
		return rank(candidates, scoring, o);
	}

	private void resolve(Token t) {
		byte[] bytes = Terms.utf8(t.text);
		t.exactId = terms.find(bytes);
		if (t.prefix) {
			int[] range = terms.prefixRange(bytes);
			t.prefixFrom = range[0];
			t.prefixTo = range[1];
			if (t.length <= IndexWriter.PREFIX_LIST_MAX_LENGTH || range[1] - range[0] > PREFIX_TERM_CAP) {
				t.prefixListId = terms.find(Terms.utf8(Field.PREFIX_LIST_PREFIX
						+ t.text.substring(0, t.text.offsetByCodePoints(0,
								Math.min(t.length, IndexWriter.PREFIX_LIST_MAX_LENGTH)))));
			}
		}
	}

	/// Finds terms within the edit distance Lucene calls `AUTO`: none up to 2
	/// characters, 1 up to 5, otherwise 2. The first character must match.
	private void resolveFuzzy(Token t) {
		int maxDistance = t.length <= 2 ? 0 : t.length <= 5 ? 1 : 2;
		if (maxDistance == 0 || t.fuzzyIds.length > 0 || t.exactId >= 0 || (t.prefix && t.prefixTo > t.prefixFrom)) {
			// Only words that match nothing as typed are treated as typos.
			return;
		}
		String head = t.text.substring(0, t.text.offsetByCodePoints(0, 1));
		int[] range = terms.prefixRange(Terms.utf8(head));
		var ids = new IntList();
		var distances = new IntList();
		int[] scanned = { 0 };
		terms.scan(range[0], (id, term, len) -> {
			if (id >= range[1] || scanned[0]++ > 200_000) {
				return false;
			}
			int termLength = len; // bytes; a cheap pre filter before decoding
			if (Math.abs(termLength - t.text.length()) > maxDistance + 2) {
				return true;
			}
			String candidate = new String(term, 0, len, java.nio.charset.StandardCharsets.UTF_8);
			int d = EditDistance.bounded(t.text, candidate, maxDistance);
			if (d > 0 && d <= maxDistance) {
				ids.add(id);
				distances.add(d);
			}
			return true;
		});
		t.fuzzyIds = ids.toArray();
		t.fuzzyDistances = distances.toArray();
	}

	/// Collects verified candidates.
	///
	/// @param missing how many tokens may be left unmatched; 0 means a strict pass.
	private void collect(List<Token> tokens, Filter filter, int missing, List<Candidate> out) {
		boolean lenient = missing > 0 || tokens.stream().anyMatch(t -> t.fuzzyIds.length > 0);
		var entries = new Forward.Entries();
		var docIds = new IntList();
		var seen = new HashSet<Integer>();
		Token driver = lenient ? null : cheapest(tokens);
		// Phases, each checked in importance order until MATCH_TARGET matches:
		// 0: places with the driving word exactly in a name (only for a prefix driver,
		//    so a place named exactly "Ott" is not crowded out by important "Ott..." ones),
		// 1: primary postings, 2: secondary postings.
		for (int phase = 0; phase < 3; phase++) {
			boolean secondary = phase == 2;
			docIds.clear();
			if (phase == 0) {
				if (driver == null || !driver.prefix || driver.exactId < 0) {
					continue;
				}
				terms.docs(driver.exactId, false, CANDIDATE_CAP, docIds);
			}
			else if (lenient) {
				// Words such as "de" or "route" match a large share of all places; they
				// still have to match, but reading their postings would cost more than
				// checking them on the candidates the rarer words produce.
				long common = Math.max(LENIENT_DRIVER_CAP, docs.count() / 100);
				boolean anyRare = tokens.stream().anyMatch(t -> cost(t) <= common || t.fuzzyIds.length > 0);
				for (Token t : tokens) {
					if (!anyRare || cost(t) <= common || t.fuzzyIds.length > 0) {
						readDocs(t, secondary, true, docIds);
					}
				}
			}
			else {
				readDocs(java.util.Objects.requireNonNull(driver), secondary, false, docIds);
			}
			docIds.sortUniqueTruncate(CANDIDATE_CAP);
			for (int i = 0; i < docIds.size() && out.size() < MATCH_TARGET; i++) {
				int doc = docIds.get(i);
				if (!filter.passesFixed(doc)) {
					continue;
				}
				forward.read(doc, entries);
				if (!filter.passesCategories(entries)) {
					continue;
				}
				boolean address = (docs.flags(doc) & DocCodec.FLAG_HOUSENUMBER) != 0;
				TokenMatch[] matches = match(tokens, entries, missing, address);
				if (matches != null && seen.add(doc)) {
					out.add(new Candidate(doc, matches));
				}
			}
			if (out.size() >= MATCH_TARGET) {
				return;
			}
		}
	}

	/// Picks the token whose postings are shortest to drive the strict pass.
	///
	/// A short prefix only has a capped list of docs where it starts a name, so it can
	/// drive only when it is the whole query: in `main street sp` the street must be
	/// found through `main` and then checked for a word starting with `sp` anywhere.
	private Token cheapest(List<Token> tokens) {
		Token best = tokens.getFirst();
		long bestCost = Long.MAX_VALUE;
		for (Token t : tokens) {
			long cost = tokens.size() > 1 && t.prefixListId >= 0 ? Long.MAX_VALUE - 1 : cost(t);
			if (cost < bestCost) {
				bestCost = cost;
				best = t;
			}
		}
		return best;
	}

	private long cost(Token t) {
		if (t.prefix) {
			if (t.prefixListId >= 0) {
				return terms.totalCount(t.prefixListId);
			}
			long sum = 0;
			for (int id = t.prefixFrom; id < t.prefixTo; id++) {
				sum += terms.totalCount(id);
			}
			return sum;
		}
		return t.exactId >= 0 ? terms.totalCount(t.exactId) : Long.MAX_VALUE;
	}

	private void readDocs(Token t, boolean secondary, boolean withFuzzy, IntList out) {
		if (secondary && t.prefixListId >= 0 && t.prefix) {
			// Short prefixes only have a capped primary list.
			return;
		}
		if (t.prefix) {
			if (t.prefixListId >= 0) {
				terms.docs(t.prefixListId, false, CANDIDATE_CAP, out);
			}
			else {
				for (int id = t.prefixFrom; id < t.prefixTo && out.size() < CANDIDATE_CAP; id++) {
					terms.docs(id, secondary, CANDIDATE_CAP, out);
				}
			}
		}
		else if (t.exactId >= 0) {
			terms.docs(t.exactId, secondary, CANDIDATE_CAP, out);
		}
		if (withFuzzy) {
			for (int id : t.fuzzyIds) {
				if (out.size() >= CANDIDATE_CAP) {
					break;
				}
				terms.docs(id, secondary, CANDIDATE_CAP, out);
			}
		}
	}

	/// Matches every token against a doc's terms. Each doc term satisfies at most one token.
	/// @return the matches, with `null` entries for unmatched tokens, or `null` if more
	///   than `missing` tokens are unmatched.
	private static TokenMatch @Nullable [] match(List<Token> tokens, Forward.Entries e, int missing,
			boolean address) {
		var matches = new TokenMatch[tokens.size()];
		boolean[] used = new boolean[e.size];
		int unmatched = 0;
		for (int ti = 0; ti < tokens.size(); ti++) {
			Token t = tokens.get(ti);
			TokenMatch repeat = repeatedMatch(tokens, matches, ti);
			if (repeat != null) {
				// A word repeated in the query, as in "Meyrin 171, Meyrin", may match the
				// same term again: the forward index keeps each term once per place.
				matches[ti] = new TokenMatch(t, repeat.quality(), repeat.field());
				continue;
			}
			double best = 0;
			int bestIndex = -1;
			int bestField = 0;
			if (t.exactId >= 0) {
				int i = e.lowerBound(t.exactId);
				if (i < e.size && e.termIds[i] == t.exactId && !used[i] && (e.masks[i] & t.allowed) != 0) {
					int field = Scoring.bestField(e.masks[i] & t.allowed);
					best = Scoring.fieldWeight(field, address);
					bestIndex = i;
					bestField = field;
				}
			}
			if (t.prefix && best < 1) {
				for (int i = e.lowerBound(t.prefixFrom); i < e.size && e.termIds[i] < t.prefixTo; i++) {
					if (used[i] || (e.masks[i] & t.allowed) == 0 || e.termIds[i] == t.exactId) {
						continue;
					}
					int field = Scoring.bestField(e.masks[i] & t.allowed);
					double q = Scoring.PREFIX_QUALITY * Scoring.fieldWeight(field, address);
					if (q > best) {
						best = q;
						bestIndex = i;
						bestField = field;
					}
				}
			}
			for (int f = 0; f < t.fuzzyIds.length && best < Scoring.FUZZY_QUALITY[1]; f++) {
				int i = e.lowerBound(t.fuzzyIds[f]);
				if (i < e.size && e.termIds[i] == t.fuzzyIds[f] && !used[i] && (e.masks[i] & t.allowed) != 0) {
					int field = Scoring.bestField(e.masks[i] & t.allowed);
					double q = Scoring.FUZZY_QUALITY[t.fuzzyDistances[f]] * Scoring.fieldWeight(field, address);
					if (q > best) {
						best = q;
						bestIndex = i;
						bestField = field;
					}
				}
			}
			if (bestIndex < 0) {
				if (++unmatched > missing) {
					return null;
				}
				continue;
			}
			used[bestIndex] = true;
			matches[ti] = new TokenMatch(t, best, bestField);
		}
		return matches;
	}

	private static @Nullable TokenMatch repeatedMatch(List<Token> tokens, TokenMatch[] matches, int index) {
		Token t = tokens.get(index);
		for (int i = 0; i < index; i++) {
			if (matches[i] != null && tokens.get(i).text.equals(t.text) && tokens.get(i).prefix == t.prefix) {
				return matches[i];
			}
		}
		return null;
	}

	private List<Hit> rank(List<Candidate> candidates, Scoring scoring, QueryOptions o) {
		for (Candidate c : candidates) {
			c.text = Scoring.text(c.matches);
			c.score = scoring.preliminary(c, docs);
		}
		candidates.sort(Comparator.comparingDouble((Candidate c) -> -c.score).thenComparingInt(c -> c.doc));
		var top = candidates.subList(0, Math.min(candidates.size(), Math.max(RESCORE_COUNT, o.limit() * 3)));
		for (Candidate c : top) {
			c.place = docs.place(c.doc);
			scoring.rescore(c);
		}
		top.sort(Comparator.comparingDouble((Candidate c) -> -c.score).thenComparingInt(c -> c.doc));
		return finish(top, o);
	}

	private static List<Hit> finish(List<Candidate> sorted, QueryOptions o) {
		var hits = new ArrayList<Hit>(o.limit());
		var dedupe = o.dedupe() ? new StreetDedupe() : null;
		for (Candidate c : sorted) {
			Place p = c.place;
			if (p == null || (dedupe != null && !dedupe.keep(p))) {
				continue;
			}
			hits.add(new Hit(p, c.score, c.explanation));
			if (hits.size() >= o.limit()) {
				break;
			}
		}
		return hits;
	}

	// ---------------------------------------------------------------- filter only

	private List<Hit> byFilterOnly(Filter filter, @Nullable LocationBias bias, QueryOptions o) {
		var candidates = new ArrayList<Candidate>();
		var entries = new Forward.Entries();
		var none = new TokenMatch[0];
		IntPredicateWithStop accept = doc -> {
			if (filter.passesFixed(doc)) {
				forward.read(doc, entries);
				if (filter.passesCategories(entries)) {
					candidates.add(new Candidate(doc, none));
				}
			}
			return candidates.size() < MATCH_TARGET;
		};
		BBox bbox = filter.filter.bbox();
		if (bbox != null) {
			tree.within(Layout.toE7(bbox.minLon()), Layout.toE7(bbox.minLat()), Layout.toE7(bbox.maxLon()),
					Layout.toE7(bbox.maxLat()), accept::test);
		}
		else if (bias != null && bias.isActive()) {
			tree.nearest(bias.point().lon(), bias.point().lat(), bias.radiusKm() + 10 * bias.decayKm(),
					(doc, km) -> accept.test(doc));
		}
		else {
			var docIds = new IntList();
			for (int[] group : filter.includeIds) {
				for (int id : group) {
					if (id >= 0) {
						terms.docs(id, true, CANDIDATE_CAP, docIds);
					}
				}
				break; // the first group is enough to generate candidates
			}
			if (filter.includeIds.length == 0) {
				for (int doc = 0; doc < docs.count() && doc < CANDIDATE_CAP; doc++) {
					docIds.add(doc);
				}
			}
			docIds.sortUniqueTruncate(CANDIDATE_CAP);
			int i = 0;
			while (i < docIds.size() && accept.test(docIds.get(i))) {
				i++;
			}
		}
		var scoring = new Scoring(o.language(), bias, null, false, o.explain());
		return rank(candidates, scoring, o);
	}

	@FunctionalInterface
	private interface IntPredicateWithStop {

		boolean test(int doc);

	}

	// ---------------------------------------------------------------- reverse

	List<Hit> reverse(ReverseQuery q) {
		QueryOptions o = q.options();
		var filter = new Filter(o.filter());
		var entries = new Forward.Entries();
		var found = new ArrayList<Candidate>();
		var dedupe = o.dedupe() ? new StreetDedupe() : null;
		tree.nearest(q.point().lon(), q.point().lat(), q.radiusKm(), (doc, km) -> {
			if (!filter.passesFixed(doc)) {
				return true;
			}
			forward.read(doc, entries);
			if (!filter.passesCategories(entries)) {
				return true;
			}
			Place p = docs.place(doc);
			if (q.where() != ReverseQuery.ANY && !q.where().test(p)) {
				return true;
			}
			if (dedupe != null && !dedupe.keep(p)) {
				return true;
			}
			var c = new Candidate(doc, new TokenMatch[0]);
			c.place = p;
			c.score = 1.0 / (1.0 + km);
			if (o.explain()) {
				c.explanation = String.format(Locale.ROOT, "distance=%.4fkm", km);
			}
			found.add(c);
			return found.size() < o.limit();
		});
		var hits = new ArrayList<Hit>(found.size());
		for (Candidate c : found) {
			hits.add(new Hit(java.util.Objects.requireNonNull(c.place), c.score, c.explanation));
		}
		return hits;
	}

	// ---------------------------------------------------------------- filters

	/// A [PlaceFilter] prepared for fast checks against doc records.
	private final class Filter {

		final PlaceFilter filter;

		final int[] countries;

		final EnumSet<AddressType> layers;

		final int[][] includeIds;

		final int[][] excludeIds;

		Filter(PlaceFilter filter) {
			this.filter = filter;
			this.countries = filter.countryCodes().stream().mapToInt(DocCodec::packCountry).toArray();
			this.layers = filter.layers().isEmpty() ? EnumSet.noneOf(AddressType.class) : EnumSet.copyOf(filter.layers());
			this.includeIds = categoryIds(filter.includeCategories());
			this.excludeIds = categoryIds(filter.excludeCategories());
		}

		private int[][] categoryIds(List<Set<String>> groups) {
			int[][] out = new int[groups.size()][];
			for (int g = 0; g < out.length; g++) {
				out[g] = groups.get(g).stream()
					.mapToInt(c -> terms.find(Terms.utf8(Field.CATEGORY_PREFIX + c)))
					.toArray();
			}
			return out;
		}

		boolean passesFixed(int doc) {
			if (countries.length > 0) {
				int packed = docs.packedCountry(doc);
				boolean ok = false;
				for (int c : countries) {
					ok |= c == packed;
				}
				if (!ok) {
					return false;
				}
			}
			if (!layers.isEmpty() && !layers.contains(docs.addressType(doc))) {
				return false;
			}
			BBox bbox = filter.bbox();
			if (bbox != null && !bbox.contains(docs.lon(doc), docs.lat(doc))) {
				return false;
			}
			return filter.tagFilters().isEmpty() || filter.testTags(docs.osmKey(doc), docs.osmValue(doc));
		}

		boolean passesCategories(Forward.Entries e) {
			for (int[] group : includeIds) {
				boolean any = false;
				for (int id : group) {
					any |= id >= 0 && e.mask(id) != 0;
				}
				if (!any) {
					return false;
				}
			}
			for (int[] group : excludeIds) {
				boolean all = group.length > 0;
				for (int id : group) {
					all &= id >= 0 && e.mask(id) != 0;
				}
				if (all) {
					return false;
				}
			}
			return true;
		}

	}

	/// Removes repeated streets: long streets are split into many OSM ways that all
	/// carry the same name.
	private static final class StreetDedupe {

		private final Set<String> seen = new HashSet<>();

		boolean keep(Place p) {
			if (!p.osmKey().equals("highway")) {
				return true;
			}
			String name = p.names().get(Place.DEFAULT_NAME);
			if (name == null) {
				return true;
			}
			String postcode = p.postcode() == null ? "" : p.postcode();
			int space = postcode.indexOf(' ');
			if (space > 0) {
				// Compare postcode districts where full postcodes split streets: NL 1234 AB, GB SW1A 1AA.
				if ("NL".equals(p.countryCode())) {
					postcode = postcode.substring(0, space);
				}
				else if ("GB".equals(p.countryCode()) && postcode.length() > space + 2) {
					postcode = postcode.substring(0, space + 2);
				}
			}
			return seen.add(p.osmValue() + ':' + postcode + ':' + name + ':' + p.countryCode());
		}

	}

}
