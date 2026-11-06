/// Measures result quality by self retrieval; see [ai.snaphop.geon.quality.QualityReport].
module ai.snaphop.geon.quality {
	requires ai.snaphop.geon.dump;
	requires ai.snaphop.geon;
	requires io.avaje.json;
	requires java.net.http;
	requires static org.jspecify;
}
