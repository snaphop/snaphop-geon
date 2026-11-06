package ai.snaphop.geon;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/// A single searchable entry: what an import produces and what a search returns.
///
/// One source object can produce several places. For example an OpenStreetMap
/// building tagged with two house numbers becomes two places that share the same
/// [#placeId()].
///
/// ## Names
///
/// [#names()] and the values of [#address()] are maps from a name key to a name.
/// The keys are:
///
/// * `default`: the name without a language, from the OSM `name` tag.
/// * a language code such as `en` or `de`, from `name:en`, `name:de` and so on.
///   Only the languages chosen at import time are kept.
/// * for [#names()] only: `alt`, `int`, `loc`, `old`, `reg` and `housename`,
///   from `alt_name`, `int_name` and the other OSM name variants.
///
/// @param placeId identifier of the source object in the generator database, or
///   `null` if the dump did not provide one.
/// @param osmType `N`, `W` or `R` for OpenStreetMap nodes, ways and relations, or
///   another single letter for other sources. `null` if unknown.
/// @param osmId the OpenStreetMap ID, or `-1` if unknown.
/// @param osmKey the main OSM tag key, for example `amenity`.
/// @param osmValue the main OSM tag value, for example `restaurant`.
/// @param addressType the place's own level in the address hierarchy.
/// @param importance relative importance between `0` and `1`.
/// @param names the place's names, see above. Empty for plain addresses.
/// @param houseNumber the house number, if this place is an address.
/// @param postcode the postcode.
/// @param countryCode upper case ISO 3166-1 alpha-2 country code.
/// @param address the names of the places this place lies in, by level.
/// @param context further names that should find this place in a search but are
///   not shown, for example alternative names of the enclosing city.
/// @param categories dot separated category paths, for example `osm.amenity.cafe`.
/// @param centroid the place's representative point.
/// @param bbox the place's extent, or `null` for points.
/// @param geometryJson the full GeoJSON geometry, if geometries were imported.
/// @param extraJson a JSON object of extra tags to return with the place, if any.
public record Place(
		@Nullable String placeId,
		@Nullable String osmType,
		long osmId,
		String osmKey,
		String osmValue,
		AddressType addressType,
		double importance,
		Map<String, String> names,
		@Nullable String houseNumber,
		@Nullable String postcode,
		@Nullable String countryCode,
		Map<AddressType, Map<String, String>> address,
		Set<String> context,
		Set<String> categories,
		GeoPoint centroid,
		@Nullable BBox bbox,
		@Nullable String geometryJson,
		@Nullable String extraJson) {

	/// The name key for names without a language.
	public static final String DEFAULT_NAME = "default";

	/// Name keys that hold name variants rather than translations, in the order a
	/// display name falls back to them.
	public static final List<String> NAME_VARIANTS = List.of("housename", "int", "loc", "reg", "alt", "old");

	/// Copies the collections so a place is immutable.
	public Place {
		Objects.requireNonNull(osmKey, "osmKey");
		Objects.requireNonNull(osmValue, "osmValue");
		Objects.requireNonNull(addressType, "addressType");
		Objects.requireNonNull(centroid, "centroid");
		names = Map.copyOf(names);
		var addressCopy = new EnumMap<AddressType, Map<String, String>>(AddressType.class);
		address.forEach((k, v) -> addressCopy.put(k, Map.copyOf(v)));
		address = java.util.Collections.unmodifiableMap(addressCopy);
		context = Set.copyOf(context);
		categories = Set.copyOf(categories);
	}

	/// Whether a search can find this place: it needs a name or a house number.
	/// @return `true` if searchable.
	public boolean isSearchable() {
		return houseNumber != null || !names.isEmpty();
	}

	/// Picks the best name for display in the given language.
	///
	/// The order is: `language`, then `fallbackLanguage`, then `default`, then the
	/// name variants in [#NAME_VARIANTS] order.
	///
	/// @param language the requested language.
	/// @param fallbackLanguage a second choice, or `null`.
	/// @return the name, or `null` if the place has no names.
	public @Nullable String displayNameOrNull(String language, @Nullable String fallbackLanguage) {
		String name = localizedOrNull(names, language, fallbackLanguage);
		if (name != null) {
			return name;
		}
		for (String variant : NAME_VARIANTS) {
			name = names.get(variant);
			if (name != null) {
				return name;
			}
		}
		return null;
	}

	/// Picks the best name of an address part for display in the given language.
	/// @param type the address level.
	/// @param language the requested language.
	/// @param fallbackLanguage a second choice, or `null`.
	/// @return the name, or `null` if the place has no such address part.
	public @Nullable String addressNameOrNull(AddressType type, String language, @Nullable String fallbackLanguage) {
		Map<String, String> part = address.get(type);
		return part == null ? null : localizedOrNull(part, language, fallbackLanguage);
	}

	private static @Nullable String localizedOrNull(Map<String, String> names, String language,
			@Nullable String fallbackLanguage) {
		String name = names.get(language);
		if (name == null && fallbackLanguage != null) {
			name = names.get(fallbackLanguage);
		}
		if (name == null) {
			name = names.get(DEFAULT_NAME);
		}
		return name;
	}

	/// Starts a builder.
	/// @return a new builder with no fields set.
	public static Builder builder() {
		return new Builder();
	}

	/// Starts a builder with every field copied from this place.
	/// @return a new builder.
	public Builder toBuilder() {
		var b = new Builder();
		b.placeId = placeId;
		b.osmType = osmType;
		b.osmId = osmId;
		b.osmKey = osmKey;
		b.osmValue = osmValue;
		b.addressType = addressType;
		b.importance = importance;
		b.names.putAll(names);
		b.houseNumber = houseNumber;
		b.postcode = postcode;
		b.countryCode = countryCode;
		b.address.putAll(address);
		b.context.addAll(context);
		b.categories.addAll(categories);
		b.centroid = centroid;
		b.bbox = bbox;
		b.geometryJson = geometryJson;
		b.extraJson = extraJson;
		return b;
	}

	/// Builds a [Place]. Not thread safe.
	public static final class Builder {

		private @Nullable String placeId;

		private @Nullable String osmType;

		private long osmId = -1;

		private String osmKey = "place";

		private String osmValue = "yes";

		private AddressType addressType = AddressType.OTHER;

		private double importance;

		private final Map<String, String> names = new LinkedHashMap<>();

		private @Nullable String houseNumber;

		private @Nullable String postcode;

		private @Nullable String countryCode;

		private final Map<AddressType, Map<String, String>> address = new EnumMap<>(AddressType.class);

		private final Set<String> context = new LinkedHashSet<>();

		private final Set<String> categories = new LinkedHashSet<>();

		private @Nullable GeoPoint centroid;

		private @Nullable BBox bbox;

		private @Nullable String geometryJson;

		private @Nullable String extraJson;

		private Builder() {
		}

		/// See [Place#placeId()].
		/// @param placeId the ID.
		/// @return this.
		public Builder placeId(@Nullable String placeId) {
			this.placeId = placeId;
			return this;
		}

		/// See [Place#osmType()] and [Place#osmId()].
		/// @param osmType `N`, `W`, `R` or another source letter.
		/// @param osmId the ID.
		/// @return this.
		public Builder osm(@Nullable String osmType, long osmId) {
			this.osmType = osmType;
			this.osmId = osmId;
			return this;
		}

		/// See [Place#osmKey()] and [Place#osmValue()]. Defaults to `place=yes`.
		/// @param key the tag key.
		/// @param value the tag value.
		/// @return this.
		public Builder tag(String key, String value) {
			this.osmKey = key;
			this.osmValue = value;
			return this;
		}

		/// See [Place#addressType()]. Defaults to [AddressType#OTHER].
		/// @param addressType the type.
		/// @return this.
		public Builder addressType(AddressType addressType) {
			this.addressType = addressType;
			return this;
		}

		/// See [Place#importance()].
		/// @param importance between `0` and `1`.
		/// @return this.
		public Builder importance(double importance) {
			this.importance = importance;
			return this;
		}

		/// Adds or replaces a name. See [Place#names()] for the keys.
		/// @param key the name key.
		/// @param name the name.
		/// @return this.
		public Builder name(String key, String name) {
			names.put(key, name);
			return this;
		}

		/// Replaces all names.
		/// @param names the names.
		/// @return this.
		public Builder names(Map<String, String> names) {
			this.names.clear();
			this.names.putAll(names);
			return this;
		}

		/// See [Place#houseNumber()].
		/// @param houseNumber the house number or `null`.
		/// @return this.
		public Builder houseNumber(@Nullable String houseNumber) {
			this.houseNumber = houseNumber == null || houseNumber.isEmpty() ? null : houseNumber;
			return this;
		}

		/// See [Place#postcode()].
		/// @param postcode the postcode or `null`.
		/// @return this.
		public Builder postcode(@Nullable String postcode) {
			this.postcode = postcode;
			return this;
		}

		/// See [Place#countryCode()]. Stored upper case.
		/// @param countryCode the code or `null`.
		/// @return this.
		public Builder countryCode(@Nullable String countryCode) {
			this.countryCode = countryCode == null ? null : countryCode.toUpperCase(java.util.Locale.ROOT);
			return this;
		}

		/// Sets the names of one address level, replacing what was there.
		/// @param type the level.
		/// @param names the names of that level, see [Place#names()] for the keys.
		/// @return this.
		public Builder address(AddressType type, Map<String, String> names) {
			address.put(type, names);
			return this;
		}

		/// Removes all address levels.
		/// @return this.
		public Builder clearAddress() {
			address.clear();
			return this;
		}

		/// Adds a context name. See [Place#context()].
		/// @param name the name.
		/// @return this.
		public Builder context(String name) {
			context.add(name);
			return this;
		}

		/// Adds a category. See [Place#categories()].
		/// @param category the category path.
		/// @return this.
		public Builder category(String category) {
			categories.add(category);
			return this;
		}

		/// See [Place#centroid()]. Required.
		/// @param centroid the point.
		/// @return this.
		public Builder centroid(GeoPoint centroid) {
			this.centroid = centroid;
			return this;
		}

		/// See [Place#bbox()].
		/// @param bbox the extent or `null`.
		/// @return this.
		public Builder bbox(@Nullable BBox bbox) {
			this.bbox = bbox;
			return this;
		}

		/// See [Place#geometryJson()].
		/// @param geometryJson a GeoJSON geometry object or `null`.
		/// @return this.
		public Builder geometryJson(@Nullable String geometryJson) {
			this.geometryJson = geometryJson;
			return this;
		}

		/// See [Place#extraJson()].
		/// @param extraJson a JSON object or `null`.
		/// @return this.
		public Builder extraJson(@Nullable String extraJson) {
			this.extraJson = extraJson;
			return this;
		}

		/// The house number set so far.
		/// @return the house number or `null`.
		public @Nullable String houseNumberOrNull() {
			return houseNumber;
		}

		/// Builds the place.
		/// @return the place.
		/// @throws IllegalStateException if no centroid was set.
		public Place build() {
			GeoPoint c = centroid;
			if (c == null) {
				throw new IllegalStateException("Place " + placeId + " (" + osmType + osmId + ") has no centroid.");
			}
			return new Place(placeId, osmType, osmId, osmKey, osmValue, addressType, importance, names,
					houseNumber, postcode, countryCode, address, context, categories, c, bbox, geometryJson,
					extraJson);
		}

	}

}
