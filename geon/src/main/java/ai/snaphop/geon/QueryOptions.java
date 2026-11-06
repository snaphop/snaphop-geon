package ai.snaphop.geon;

/// Options shared by every query type.
///
/// @param language the language results are matched and shown in.
/// @param limit the maximum number of results, at least `1`.
/// @param filter restrictions on the results.
/// @param dedupe whether to drop repeated results of the same street, as happens when
///   a long street is split into several OSM ways.
/// @param explain whether each [Hit] should carry an explanation of its score.
public record QueryOptions(Language language, int limit, PlaceFilter filter, boolean dedupe, boolean explain) {

	/// The defaults: names without a language, 15 results, no filter, dedupe on.
	public static final QueryOptions DEFAULT = new QueryOptions(Language.DEFAULT, 15, PlaceFilter.NONE, true, false);

	/// Validates the limit.
	public QueryOptions {
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1 but was " + limit + ".");
		}
	}

}
