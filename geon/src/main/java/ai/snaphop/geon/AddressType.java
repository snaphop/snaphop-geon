package ai.snaphop.geon;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/// The role a place plays in an address, from a single house up to a country.
///
/// These are the address levels of [GeocodeJSON](https://github.com/geocoders/geocodejson-spec).
/// Each level also covers a range of
/// [Nominatim address ranks](https://nominatim.org/release-docs/latest/customize/Ranking/#address-rank),
/// which is how dump files that only carry a rank are mapped.
public enum AddressType {

	/// A single building or address point.
	HOUSE("house", 29, 30),
	/// A street, or a named place that takes the role of a street in addresses.
	STREET("street", 26, 28),
	/// A neighbourhood or other small named area.
	LOCALITY("locality", 22, 25),
	/// A city district or suburb.
	DISTRICT("district", 17, 21),
	/// A city, town or village.
	CITY("city", 13, 16),
	/// A county or similar administrative level.
	COUNTY("county", 10, 12),
	/// A state, province or region.
	STATE("state", 5, 9),
	/// A country.
	COUNTRY("country", 4, 4),
	/// A place outside the address hierarchy, for example a river or a mountain top.
	OTHER("other", 0, 0);

	private static final AddressType[] RANK_LOOKUP = new AddressType[31];

	static {
		java.util.Arrays.fill(RANK_LOOKUP, OTHER);
		for (AddressType type : values()) {
			for (int rank = type.minRank; rank <= type.maxRank; rank++) {
				RANK_LOOKUP[rank] = type;
			}
		}
	}

	private final String label;

	private final int minRank;

	private final int maxRank;

	AddressType(String label, int minRank, int maxRank) {
		this.label = label;
		this.minRank = minRank;
		this.maxRank = maxRank;
	}

	/// The lowercase name used in dump files and in API responses, for example `city`.
	/// @return the label.
	public String label() {
		return label;
	}

	/// Maps a Nominatim address rank to an address type.
	/// @param rank the Nominatim address rank.
	/// @return the matching type, or [#OTHER] when the rank is outside `0..30`.
	public static AddressType ofRank(int rank) {
		return rank >= 0 && rank < RANK_LOOKUP.length ? RANK_LOOKUP[rank] : OTHER;
	}

	/// Looks up a type by its [label][#label()], ignoring case.
	/// @param label the label, for example `city`.
	/// @return the type or `null` when no type has that label.
	public static @Nullable AddressType ofLabelOrNull(String label) {
		return switch (label.toLowerCase(Locale.ROOT)) {
			case "house" -> HOUSE;
			case "street" -> STREET;
			case "locality" -> LOCALITY;
			case "district" -> DISTRICT;
			case "city" -> CITY;
			case "county" -> COUNTY;
			case "state" -> STATE;
			case "country" -> COUNTRY;
			case "other" -> OTHER;
			default -> null;
		};
	}

}
