package ai.snaphop.geon;

import org.jspecify.annotations.Nullable;

/// A search for an address given as separate parts.
///
/// At least one part is required. Parts that are `null` or blank are ignored.
///
/// @param countryCode ISO 3166-1 alpha-2 country code.
/// @param state state or province.
/// @param county county.
/// @param city city, town or village.
/// @param postcode postcode.
/// @param district city district or suburb.
/// @param street street.
/// @param houseNumber house number.
/// @param bias an optional preference for results near a point.
/// @param options the shared options.
public record StructuredQuery(
		@Nullable String countryCode,
		@Nullable String state,
		@Nullable String county,
		@Nullable String city,
		@Nullable String postcode,
		@Nullable String district,
		@Nullable String street,
		@Nullable String houseNumber,
		@Nullable LocationBias bias,
		QueryOptions options) {

	/// Normalizes blank parts to `null` and requires at least one part.
	public StructuredQuery {
		countryCode = blankToNull(countryCode);
		state = blankToNull(state);
		county = blankToNull(county);
		city = blankToNull(city);
		postcode = blankToNull(postcode);
		district = blankToNull(district);
		street = blankToNull(street);
		houseNumber = blankToNull(houseNumber);
		if (countryCode == null && state == null && county == null && city == null && postcode == null
				&& district == null && street == null && houseNumber == null) {
			throw new IllegalArgumentException("A structured search needs at least one address part.");
		}
	}

	private static @Nullable String blankToNull(@Nullable String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

}
