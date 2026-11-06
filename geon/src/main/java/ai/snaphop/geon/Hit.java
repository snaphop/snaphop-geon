package ai.snaphop.geon;

import org.jspecify.annotations.Nullable;

/// A place returned by a query.
///
/// @param place the place.
/// @param score the ranking score. Only the order of scores within one response is
///   meaningful.
/// @param explanation how the score was computed, if the query asked for it.
public record Hit(Place place, double score, @Nullable String explanation) {
}
