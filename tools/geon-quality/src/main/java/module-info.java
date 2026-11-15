/// Measures result quality by self retrieval; see [ai.snaphop.geon.quality.QualityReport].
module ai.snaphop.geon.quality {
	requires ai.snaphop.geon.dump;
	requires ai.snaphop.geon;
	requires com.snaphop.jsonl;
	requires java.net.http;
	requires static org.jspecify;
}
