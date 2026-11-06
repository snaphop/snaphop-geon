/// Geon core: the place model, the query model and the [ai.snaphop.geon.GeonIndex]
/// interface that index implementations provide.
///
/// This module depends only on the JDK. API adapters (Photon compatible or native)
/// translate their requests into [ai.snaphop.geon.SearchQuery],
/// [ai.snaphop.geon.StructuredQuery] or [ai.snaphop.geon.ReverseQuery].
module ai.snaphop.geon {
	exports ai.snaphop.geon;

	requires static org.jspecify;
}
