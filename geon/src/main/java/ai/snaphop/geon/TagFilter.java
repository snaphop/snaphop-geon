package ai.snaphop.geon;

import org.jspecify.annotations.Nullable;

/// Filters places by their main OSM tag ([Place#osmKey()] and [Place#osmValue()]).
///
/// @param kind how a match is treated.
/// @param key the tag key to match, or `null` to match any key.
/// @param value the tag value to match, or `null` to match any value.
public record TagFilter(Kind kind, @Nullable String key, @Nullable String value) {

	/// Requires at least a key or a value.
	public TagFilter {
		if (key == null && value == null) {
			throw new IllegalArgumentException("A tag filter needs a key, a value or both.");
		}
		if (kind == Kind.EXCLUDE_VALUE && (key == null || value == null)) {
			throw new IllegalArgumentException("An EXCLUDE_VALUE tag filter needs both a key and a value.");
		}
	}

	/// How a [TagFilter] match is treated.
	public enum Kind {

		/// Keep places that match. Several include filters (including
		/// [#EXCLUDE_VALUE]) are alternatives: a place is kept if it matches any of them.
		INCLUDE,
		/// Drop places that match.
		EXCLUDE,
		/// Keep places that have the key but a different value. For example
		/// `key=tourism, value=hotel` keeps `tourism` places other than hotels.
		/// It is an alternative like [#INCLUDE], so another include filter can still
		/// let a hotel through.
		EXCLUDE_VALUE

	}

	/// Whether the place's tag matches this filter's key and value, ignoring [#kind()].
	/// @param osmKey the place's tag key.
	/// @param osmValue the place's tag value.
	/// @return `true` on a match.
	public boolean matches(String osmKey, String osmValue) {
		return (key == null || key.equals(osmKey)) && (value == null || value.equals(osmValue));
	}

}
