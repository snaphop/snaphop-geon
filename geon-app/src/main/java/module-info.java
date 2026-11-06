/// The `geon` command line: `import` builds an index from a dump, `serve` runs the
/// Photon compatible API. See [ai.snaphop.geon.app.Main].
module ai.snaphop.geon.app {
	requires ai.snaphop.geon.dump;
	requires ai.snaphop.geon.index;
	requires ai.snaphop.geon.photon;
	requires io.jstach.rainbowgum;
	requires static org.jspecify;
}
