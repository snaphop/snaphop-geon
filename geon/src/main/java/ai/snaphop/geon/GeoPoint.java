package ai.snaphop.geon;

/// A WGS84 coordinate.
///
/// @param lon longitude in degrees, `-180..180`.
/// @param lat latitude in degrees, `-90..90`.
public record GeoPoint(double lon, double lat) {

	private static final double EARTH_RADIUS_KM = 6371.0;

	/// Validates the coordinate range.
	/// @throws IllegalArgumentException if a value is out of range or not a number.
	public GeoPoint {
		if (!(lon >= -180 && lon <= 180) || !(lat >= -90 && lat <= 90)) {
			throw new IllegalArgumentException("Coordinate out of range: lon=" + lon + " lat=" + lat
					+ ". Expected lon in [-180,180] and lat in [-90,90].");
		}
	}

	/// Great circle distance using the haversine formula.
	/// @param other the other point.
	/// @return the distance in kilometres.
	public double distanceKm(GeoPoint other) {
		return distanceKm(lon, lat, other.lon, other.lat);
	}

	/// Great circle distance using the haversine formula, without allocating points.
	/// @param lon1 longitude of the first point.
	/// @param lat1 latitude of the first point.
	/// @param lon2 longitude of the second point.
	/// @param lat2 latitude of the second point.
	/// @return the distance in kilometres.
	public static double distanceKm(double lon1, double lat1, double lon2, double lat2) {
		double sinLat = Math.sin(Math.toRadians(lat2 - lat1) / 2);
		double sinLon = Math.sin(Math.toRadians(lon2 - lon1) / 2);
		double a = sinLat * sinLat
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
		return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

}
