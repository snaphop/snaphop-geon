package ai.snaphop.geon;

/// Prefers results near a point.
///
/// The bias is strongest within [#radiusKm()] of the point and decays exponentially
/// beyond it. `zoom` follows web map zoom levels: at a high zoom the user looks at a
/// small area and nearby results matter more.
///
/// @param point the point to prefer results near.
/// @param zoom map zoom level, clamped to `0..18`. At zoom `4` and below there is no bias.
/// @param scale how much importance still counts, clamped to `0..1`. `0` ranks by
///   distance alone; `1` ignores distance.
public record LocationBias(GeoPoint point, int zoom, double scale) {

	/// The default zoom level.
	public static final int DEFAULT_ZOOM = 12;

	/// The default importance scale.
	public static final double DEFAULT_SCALE = 0.4;

	/// Clamps zoom and scale to their ranges.
	public LocationBias {
		zoom = Math.clamp(zoom, 0, 18);
		scale = Double.isNaN(scale) ? DEFAULT_SCALE : Math.clamp(scale, 0.0, 1.0);
	}

	/// Whether the bias has any effect. A zoom of `4` or less covers a continent.
	/// @return `true` if active.
	public boolean isActive() {
		return zoom > 4;
	}

	/// The radius within which results get the full bias.
	/// @return the radius in kilometres.
	public double radiusKm() {
		return Math.pow(2.2, 18 - zoom) * 0.1;
	}

	/// The distance beyond [#radiusKm()] at which the bias has halved.
	/// @return the distance in kilometres.
	public double decayKm() {
		return Math.max(8, radiusKm() * (zoom - 3));
	}

}
