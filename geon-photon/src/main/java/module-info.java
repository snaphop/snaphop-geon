/// A [Photon](https://github.com/komoot/photon) compatible HTTP API over any
/// [ai.snaphop.geon.GeonIndex].
///
/// It serves `/api`, `/structured`, `/reverse` and `/status` with Photon's
/// parameters, validation messages and GeoJSON responses, so existing Photon clients
/// work unchanged. Start with [ai.snaphop.geon.photon.PhotonApi#builder(ai.snaphop.geon.GeonIndex)].
module ai.snaphop.geon.photon {
	exports ai.snaphop.geon.photon;

	requires transitive ai.snaphop.geon;
	requires transitive ai.snaphop.geon.http;
	requires io.avaje.json;
	requires static org.jspecify;
}
