package ai.snaphop.geon;

/// An axis aligned WGS84 bounding box.
///
/// @param minLon western edge.
/// @param minLat southern edge.
/// @param maxLon eastern edge.
/// @param maxLat northern edge.
public record BBox(double minLon, double minLat, double maxLon, double maxLat) {

	/// Normalizes the corners so that `min <= max` on both axes.
	public BBox {
		if (minLon > maxLon) {
			double t = minLon;
			minLon = maxLon;
			maxLon = t;
		}
		if (minLat > maxLat) {
			double t = minLat;
			minLat = maxLat;
			maxLat = t;
		}
	}

	/// Whether the box contains the coordinate, edges included.
	/// @param lon longitude.
	/// @param lat latitude.
	/// @return `true` if inside.
	public boolean contains(double lon, double lat) {
		return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
	}

	/// Whether the box has zero area, as a box around a single point does.
	/// @return `true` if degenerate.
	public boolean isEmpty() {
		return minLon == maxLon || minLat == maxLat;
	}

}
