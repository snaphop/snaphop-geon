/// Geon's default [ai.snaphop.geon.GeonIndex]: one immutable file, written once by
/// [ai.snaphop.geon.index.IndexWriter] and memory mapped by [ai.snaphop.geon.index.FileIndex].
///
/// The file is read through the foreign memory API, so the index lives in the OS
/// page cache rather than on the Java heap, and opening it costs almost nothing.
module ai.snaphop.geon.index {
	exports ai.snaphop.geon.index;

	requires transitive ai.snaphop.geon;
	requires static org.jspecify;
}
