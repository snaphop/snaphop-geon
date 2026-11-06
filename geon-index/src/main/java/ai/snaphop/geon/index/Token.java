package ai.snaphop.geon.index;

import java.util.List;

/// A query token and the term IDs it can match.
final class Token {

	final String text;

	final int length;

	final boolean prefix;

	final int allowed;

	/// Which comma separated part of the query the token is in, 0 for the first.
	int segment;

	int exactId = -1;

	int prefixFrom;

	int prefixTo;

	/// ID of the capped prefix list term for short prefixes, or -1.
	int prefixListId = -1;

	int[] fuzzyIds = new int[0];

	int[] fuzzyDistances = new int[0];

	/// Terms of synonyms of the word; they match like the word itself.
	int[] synonymIds = new int[0];

	List<String> synonymTexts = List.of();

	/// Category terms the word classifies, such as `osm.railway.station` for `station`.
	int[] classIds = new int[0];

	Token(String text, boolean prefix, int allowed) {
		this.text = text;
		this.length = text.codePointCount(0, text.length());
		this.prefix = prefix;
		this.allowed = allowed;
	}

	boolean hasStrictTerms() {
		return exactId >= 0 || synonymIds.length > 0 || classIds.length > 0 || (prefix && prefixTo > prefixFrom);
	}

}
