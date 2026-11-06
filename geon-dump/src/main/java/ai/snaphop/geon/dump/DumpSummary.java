package ai.snaphop.geon.dump;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/// What a [DumpReader] found in a dump file.
///
/// @param generator the program that wrote the dump, for example `photon` or `nominatim`.
/// @param dataTimestamp when the data in the dump was current.
/// @param placeCount places handed to the sink.
/// @param skippedCount source places that produced no place, because they had neither
///   a name nor a house number, no centroid, or failed the country filter.
public record DumpSummary(@Nullable String generator, @Nullable Instant dataTimestamp, long placeCount,
		long skippedCount) {
}
