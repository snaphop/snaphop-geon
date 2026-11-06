package ai.snaphop.geon.dump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.GeoPoint;
import ai.snaphop.geon.Place;

/// Turns [RawPlace]s into [Place]s, following how Photon interprets a dump.
///
/// This is where dump specific conventions live:
///
/// * which OSM name tags become which [Place#names()] keys,
/// * which address keys map to which [AddressType],
/// * how one OSM object with several house numbers becomes several places.
///
/// Not thread safe: it keeps the address line cache of the current country.
final class PlaceAssembler {

	private static final Pattern CATEGORY = Pattern.compile("[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+");

	/// A house number value is rejected when any comma separated part has three or more
	/// characters that are neither digits nor commas, which catches notes such as `unknown`.
	private static final Pattern HOUSENUMBER_JUNK = Pattern.compile("(\\A|.*,)[^\\d,]{3,}(,.*|\\Z)");

	private static final Pattern HOUSENUMBER_SPLIT = Pattern.compile("\\s*[;,]\\s*");

	private static final Map<String, AddressType> ADDRESS_KEYS = Map.ofEntries(
			Map.entry("street", AddressType.STREET),
			Map.entry("city", AddressType.CITY),
			Map.entry("suburb", AddressType.DISTRICT),
			Map.entry("neighbourhood", AddressType.LOCALITY),
			Map.entry("county", AddressType.COUNTY),
			Map.entry("state", AddressType.STATE),
			Map.entry("province", AddressType.STATE),
			Map.entry("other", AddressType.OTHER),
			Map.entry("district", AddressType.OTHER),
			Map.entry("hamlet", AddressType.OTHER),
			Map.entry("subdistrict", AddressType.OTHER),
			Map.entry("municipality", AddressType.OTHER),
			Map.entry("region", AddressType.OTHER),
			Map.entry("ward", AddressType.OTHER),
			Map.entry("village", AddressType.OTHER),
			Map.entry("subward", AddressType.OTHER),
			Map.entry("block", AddressType.OTHER),
			Map.entry("quarter", AddressType.OTHER));

	private static final String[][] NAME_VARIANT_TAGS = {
			{ "alt", "_place_alt_name", "alt_name" },
			{ "int", "_place_int_name", "int_name" },
			{ "loc", "_place_loc_name", "loc_name" },
			{ "old", "_place_old_name", "old_name" },
			{ "reg", "_place_reg_name", "reg_name" },
			{ "housename", "addr:housename" } };

	private final Set<String> languages;

	private final Map<String, Map<String, String>> countryNames = new HashMap<>();

	private final Map<String, AddressLine> addressLines = new HashMap<>();

	/// A place referenced by other places' `addresslines`.
	private record AddressLine(AddressType type, Map<String, String> names, @Nullable String context) {
	}

	PlaceAssembler(Set<String> languages) {
		this.languages = Set.copyOf(languages);
	}

	/// Replaces the country names, from a `CountryInfo` document.
	void putCountry(String countryCode, Map<String, String> tags) {
		countryNames.put(countryCode.toUpperCase(java.util.Locale.ROOT), names(tags, true));
	}

	/// Forgets address lines, called when a dump sorted by country moves to the next country.
	void clearAddressLines() {
		addressLines.clear();
	}

	/// Remembers a place so later places can reference it in their `addresslines`.
	void rememberAddressLine(RawPlace raw) {
		AddressType type = raw.addressType;
		if (raw.placeId == null || raw.names.isEmpty() || type == null || type == AddressType.HOUSE
				|| type == AddressType.OTHER) {
			return;
		}
		Map<String, String> names = localeNames(raw.names);
		if (!names.isEmpty()) {
			// ISO3166-2 makes state codes such as US-CA searchable.
			addressLines.put(raw.placeId, new AddressLine(type, names, raw.names.get("ISO3166-2")));
		}
	}

	/// Assembles the places for one element of a `Place` document.
	///
	/// @param splitAddresses `true` when the document held a single object rather than an
	///   array. Only then are multiple house numbers split into several places, which is
	///   what Photon does.
	/// @return the places, possibly none.
	List<Place> assemble(RawPlace raw, boolean splitAddresses) {
		if (raw.centroid == null) {
			return List.of();
		}
		var base = Place.builder()
			.placeId(raw.placeId)
			.osm(raw.objectType, raw.objectId)
			.addressType(raw.addressType == null ? AddressType.OTHER : raw.addressType)
			.importance(raw.importance)
			.names(names(raw.names, false))
			.houseNumber(raw.houseNumber)
			.countryCode(raw.countryCode)
			.geometryJson(raw.geometryJson)
			.extraJson(raw.extraJson);
		setTag(base, raw);
		try {
			base.centroid(new GeoPoint(raw.centroid[0], raw.centroid[1]));
		}
		catch (IllegalArgumentException e) {
			return List.of();
		}
		double[] box = raw.geometryEnvelope != null ? raw.geometryEnvelope : raw.bbox;
		if (box != null) {
			base.bbox(new BBox(box[0], box[1], box[2], box[3]));
		}
		var address = new Address(raw.addressType == null ? AddressType.OTHER : raw.addressType);
		address.postcode = raw.postcode;
		for (String id : raw.addressLinePlaceIds) {
			AddressLine line = addressLines.get(id);
			if (line != null) {
				address.addLine(line);
			}
		}
		address.addEntries(raw.address);
		base.postcode(address.postcode);
		address.parts.forEach(base::address);
		address.context.forEach(base::context);
		if (raw.countryCode != null) {
			Map<String, String> country = countryNames.get(raw.countryCode.toUpperCase(java.util.Locale.ROOT));
			if (country != null) {
				base.address(AddressType.COUNTRY, country);
			}
		}
		Place place = base.build();
		if (!splitAddresses) {
			return place.isSearchable() ? List.of(place) : List.of();
		}
		return split(place, firstValues(raw.address));
	}

	private static void setTag(Place.Builder b, RawPlace raw) {
		var categories = raw.categories.stream().filter(c -> CATEGORY.matcher(c).matches()).toList();
		categories.forEach(b::category);
		if (raw.osmKey != null) {
			b.tag(raw.osmKey, raw.osmValue == null ? "yes" : raw.osmValue);
			return;
		}
		for (String c : categories) {
			if (c.startsWith("osm.")) {
				String[] parts = c.split("\\.");
				b.tag(parts[1], parts.length > 2 ? parts[2] : "yes");
				return;
			}
		}
	}

	/// The address parts and context of one place while they are being collected.
	private final class Address {

		final AddressType ownType;

		final Map<AddressType, Map<String, String>> parts = new EnumMap<>(AddressType.class);

		final Set<String> context = new LinkedHashSet<>();

		@Nullable String postcode;

		Address(AddressType ownType) {
			this.ownType = ownType;
		}

		void addLine(AddressLine line) {
			if (line.type == ownType || parts.putIfAbsent(line.type, line.names) != null) {
				context.addAll(line.names.values());
			}
			if (line.context != null && !line.context.isBlank()) {
				context.add(line.context.strip());
			}
		}

		void addEntries(List<Map.Entry<String, String>> entries) {
			var overlay = new EnumMap<AddressType, Map<String, String>>(AddressType.class);
			for (var entry : entries) {
				String key = entry.getKey();
				String value = entry.getValue().strip();
				if (value.isEmpty()) {
					continue;
				}
				if (key.equals("postcode")) {
					postcode = value;
					continue;
				}
				int colon = key.indexOf(':');
				String baseKey = colon >= 0 ? key.substring(0, colon) : key;
				AddressType type = ADDRESS_KEYS.get(baseKey);
				if (type == null && baseKey.startsWith("other")) {
					type = AddressType.OTHER;
				}
				if (type == null) {
					continue;
				}
				String language = colon >= 0 ? key.substring(colon + 1) : Place.DEFAULT_NAME;
				if (colon >= 0 && !languages.contains(language)) {
					continue;
				}
				if (type == AddressType.OTHER
						|| overlay.computeIfAbsent(type, k -> new LinkedHashMap<>()).putIfAbsent(language, value) != null) {
					context.add(value);
				}
			}
			overlay.forEach((type, names) -> {
				Map<String, String> existing = parts.get(type);
				if (existing == null) {
					parts.put(type, names);
					return;
				}
				var merged = new LinkedHashMap<>(existing);
				names.forEach((language, name) -> {
					String old = merged.put(language, name);
					if (old != null && !old.equals(name)) {
						context.add(old);
					}
				});
				parts.put(type, merged);
			});
		}

	}

	/// Splits an address with several house numbers into one place per house number.
	private List<Place> split(Place place, Map<String, String> address) {
		var out = new ArrayList<Place>(1);
		String placeName = address.get("place");
		if (placeName != null && !placeName.isBlank()) {
			for (String hnr : houseNumbers(address.get("conscriptionnumber"))) {
				out.add(withStreet(place, placeName).houseNumber(hnr).build());
			}
		}
		if (address.containsKey("street")) {
			// In CZ and SK 'housenumber' holds the combined form such as '100/9'.
			List<String> hnrs = houseNumbers(address.get("housenumber"));
			if (hnrs.isEmpty()) {
				// A street number without a house number is a tagging error, but it exists.
				hnrs = houseNumbers(address.get("streetnumber"));
			}
			for (String hnr : hnrs) {
				out.add(place.toBuilder().houseNumber(hnr).build());
			}
		}
		if (out.isEmpty()) {
			List<String> hnrs = houseNumbers(address.get("housenumber"));
			if (!hnrs.isEmpty()) {
				String block = address.get("block_number");
				Place.Builder template;
				if (block != null && !block.isBlank()) {
					// Block numbers replace streets. A street in the same address is a tagging error.
					template = withStreet(place, block);
				}
				else if (placeName != null && !placeName.isBlank() && !address.containsKey("street")) {
					template = withStreet(place, placeName);
				}
				else {
					template = place.toBuilder();
				}
				for (String hnr : hnrs) {
					out.add(template.houseNumber(hnr).build());
				}
			}
		}
		if (out.isEmpty() && place.isSearchable()) {
			out.add(place);
		}
		return out;
	}

	private static Place.Builder withStreet(Place place, String street) {
		var b = place.toBuilder().clearAddress();
		place.address().forEach((type, names) -> {
			if (type != AddressType.STREET) {
				b.address(type, names);
			}
		});
		return b.address(AddressType.STREET, Map.of(Place.DEFAULT_NAME, street));
	}

	private static List<String> houseNumbers(@Nullable String value) {
		if (value == null || value.isBlank() || HOUSENUMBER_JUNK.matcher(value).find()) {
			return List.of();
		}
		return Arrays.stream(HOUSENUMBER_SPLIT.split(value))
			.filter(s -> !s.isBlank() && s.length() < 20)
			.map(String::strip)
			.toList();
	}

	private static Map<String, String> firstValues(List<Map.Entry<String, String>> entries) {
		var out = new HashMap<String, String>();
		for (var e : entries) {
			out.putIfAbsent(e.getKey(), e.getValue());
		}
		return out;
	}

	/// The display names of a place: `default`, the import languages and, unless
	/// `localeOnly`, the name variants.
	private Map<String, String> names(Map<String, String> tags, boolean localeOnly) {
		Map<String, String> out = localeNames(tags);
		if (!localeOnly) {
			for (String[] variant : NAME_VARIANT_TAGS) {
				putFirst(out, variant[0], tags, Arrays.copyOfRange(variant, 1, variant.length));
			}
		}
		return out;
	}

	private Map<String, String> localeNames(Map<String, String> tags) {
		var out = new LinkedHashMap<String, String>();
		putFirst(out, Place.DEFAULT_NAME, tags, "_place_name", "name");
		for (String language : languages) {
			putFirst(out, language, tags, "_place_name:" + language, "name:" + language);
		}
		return out;
	}

	private static void putFirst(Map<String, String> out, String key, Map<String, String> tags, String... tagKeys) {
		for (String tag : tagKeys) {
			String value = tags.get(tag);
			if (value != null && !value.isBlank()) {
				out.put(key, value.strip());
				return;
			}
		}
	}

}
