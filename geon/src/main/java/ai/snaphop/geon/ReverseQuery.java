package ai.snaphop.geon;

import java.util.function.Predicate;

/// Finds the places nearest to a point.
///
/// @param point the point.
/// @param radiusKm only places whose centroid is within this distance are returned.
/// @param sortByDistance `true` to order results by distance; `false` lets the index
///   return any places within the radius, which can be cheaper.
/// @param where an extra condition on the results, applied after [QueryOptions#filter()].
///   Use [#ANY] for none.
/// @param options the shared options.
public record ReverseQuery(GeoPoint point, double radiusKm, boolean sortByDistance, Predicate<Place> where,
		QueryOptions options) {

	/// The condition that accepts every place.
	public static final Predicate<Place> ANY = p -> true;

	/// The default search radius in kilometres.
	public static final double DEFAULT_RADIUS_KM = 1.0;

	/// Validates the radius.
	public ReverseQuery {
		if (!(radiusKm > 0)) {
			throw new IllegalArgumentException("radiusKm must be greater than 0 but was " + radiusKm + ".");
		}
	}

}
