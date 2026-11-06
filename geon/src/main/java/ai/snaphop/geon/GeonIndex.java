package ai.snaphop.geon;

import java.util.List;

/// A searchable collection of places.
///
/// Implementations must be thread safe: one index serves concurrent queries.
/// Results are ordered best first and never exceed [QueryOptions#limit()].
public interface GeonIndex extends AutoCloseable {

	/// Describes the index.
	/// @return the index facts.
	IndexInfo info();

	/// Runs a free text search.
	/// @param query the query.
	/// @return the hits, best first.
	List<Hit> search(SearchQuery query);

	/// Runs a structured address search.
	/// @param query the query.
	/// @return the hits, best first.
	List<Hit> search(StructuredQuery query);

	/// Finds places near a point.
	/// @param query the query.
	/// @return the hits, nearest or best first.
	List<Hit> reverse(ReverseQuery query);

	/// Releases the index's resources. Queries after closing fail.
	@Override
	void close();

}
