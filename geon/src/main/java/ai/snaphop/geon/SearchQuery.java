package ai.snaphop.geon;

import org.jspecify.annotations.Nullable;

/// A free text forward search, such as `berlin` or `Main Street 12, Springfield`.
///
/// The query is treated as typed so far: the last word may be incomplete unless the
/// text ends with a space.
///
/// @param text the search text, or `null` for a search by filter only, which then
///   needs [PlaceFilter#includeCategories()].
/// @param bias an optional preference for results near a point.
/// @param suggestAddresses whether to prefer house number addresses over named places.
/// @param options the shared options.
public record SearchQuery(@Nullable String text, @Nullable LocationBias bias, boolean suggestAddresses,
		QueryOptions options) {

	/// Normalizes blank text to `null` and checks that the query can match anything.
	public SearchQuery {
		if (text != null && text.isBlank()) {
			text = null;
		}
		if (text == null && options.filter().includeCategories().isEmpty()) {
			throw new IllegalArgumentException("A search needs text or at least one included category.");
		}
	}

}
