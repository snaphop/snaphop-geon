package ai.snaphop.geon;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/// Restrictions a place must satisfy to be returned, shared by all query types.
///
/// An empty filter ([#NONE]) accepts everything.
///
/// @param countryCodes upper case country codes. If not empty, the place's country must
///   be one of them.
/// @param layers address types. If not empty, the place's [Place#addressType()] must be
///   one of them.
/// @param tagFilters OSM tag filters, see [TagFilter.Kind] for how they combine.
/// @param includeCategories each set is a group of alternatives; the place must match at
///   least one category of every group. A category matches if it equals one of the
///   place's categories or is a dot separated prefix of one, so `osm.amenity` matches
///   `osm.amenity.cafe`.
/// @param excludeCategories each set is a group; a place is dropped if it matches every
///   category of any one group.
/// @param bbox if not `null`, the place's centroid must lie inside it.
public record PlaceFilter(
		Set<String> countryCodes,
		Set<AddressType> layers,
		List<TagFilter> tagFilters,
		List<Set<String>> includeCategories,
		List<Set<String>> excludeCategories,
		@Nullable BBox bbox) {

	/// The filter that accepts every place.
	public static final PlaceFilter NONE = new PlaceFilter(Set.of(), Set.of(), List.of(), List.of(), List.of(), null);

	/// Copies the collections.
	public PlaceFilter {
		countryCodes = Set.copyOf(countryCodes);
		layers = layers.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(layers));
		tagFilters = List.copyOf(tagFilters);
		includeCategories = includeCategories.stream().map(Set::copyOf).toList();
		excludeCategories = excludeCategories.stream().map(Set::copyOf).toList();
	}

	/// Whether this filter accepts every place.
	/// @return `true` if empty.
	public boolean isEmpty() {
		return countryCodes.isEmpty() && layers.isEmpty() && tagFilters.isEmpty() && includeCategories.isEmpty()
				&& excludeCategories.isEmpty() && bbox == null;
	}

	/// Tests a place against every restriction.
	/// @param place the place.
	/// @return `true` if the place passes.
	public boolean test(Place place) {
		if (!countryCodes.isEmpty() && (place.countryCode() == null || !countryCodes.contains(place.countryCode()))) {
			return false;
		}
		if (!layers.isEmpty() && !layers.contains(place.addressType())) {
			return false;
		}
		if (bbox != null && !bbox.contains(place.centroid().lon(), place.centroid().lat())) {
			return false;
		}
		if (!testTags(place.osmKey(), place.osmValue())) {
			return false;
		}
		for (Set<String> group : includeCategories) {
			if (group.stream().noneMatch(c -> hasCategory(place.categories(), c))) {
				return false;
			}
		}
		for (Set<String> group : excludeCategories) {
			if (group.stream().allMatch(c -> hasCategory(place.categories(), c))) {
				return false;
			}
		}
		return true;
	}

	/// Tests only the [#tagFilters()].
	/// @param osmKey the place's tag key.
	/// @param osmValue the place's tag value.
	/// @return `true` if the tag passes.
	public boolean testTags(String osmKey, String osmValue) {
		boolean hasInclude = false;
		boolean included = false;
		for (TagFilter f : tagFilters) {
			switch (f.kind()) {
				case INCLUDE -> {
					hasInclude = true;
					included |= f.matches(osmKey, osmValue);
				}
				case EXCLUDE -> {
					if (f.matches(osmKey, osmValue)) {
						return false;
					}
				}
				case EXCLUDE_VALUE -> {
					hasInclude = true;
					included |= osmKey.equals(f.key()) && !osmValue.equals(f.value());
				}
			}
		}
		return !hasInclude || included;
	}

	/// Whether `categories` contains `category` or a category below it in the hierarchy.
	/// @param categories a place's categories.
	/// @param category the category to look for.
	/// @return `true` on a match.
	public static boolean hasCategory(Set<String> categories, String category) {
		if (categories.contains(category)) {
			return true;
		}
		for (String c : categories) {
			if (c.length() > category.length() && c.charAt(category.length()) == '.' && c.startsWith(category)) {
				return true;
			}
		}
		return false;
	}

}
