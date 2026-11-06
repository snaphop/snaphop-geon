/// Reads the [Nominatim dump format](https://github.com/komoot/photon/blob/master/docs/json-dump-format-0.1.0.md),
/// the JSON lines format that Photon imports and that GraphHopper publishes weekly
/// for the planet and per country.
///
/// Start with [ai.snaphop.geon.dump.DumpReader#builder()].
module ai.snaphop.geon.dump {
	exports ai.snaphop.geon.dump;

	requires transitive ai.snaphop.geon;
	requires io.avaje.json;
	requires static org.jspecify;
}
