package ai.snaphop.geon;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/// Facts about an opened index.
///
/// @param dataTimestamp when the source data was current, if the import knew it.
/// @param languages the languages whose names were imported, besides `default`.
/// @param hasGeometries whether places carry full geometries.
/// @param placeCount the number of places in the index.
public record IndexInfo(@Nullable Instant dataTimestamp, List<String> languages, boolean hasGeometries,
		long placeCount) {

	/// Copies the language list.
	public IndexInfo {
		languages = List.copyOf(languages);
	}

}
