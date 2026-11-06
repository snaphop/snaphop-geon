package ai.snaphop.geon.index;

import ai.snaphop.geon.AddressType;

/// Where in a place a term occurs. Stored as bits of a mask next to each term.
enum Field {

	/// One of the place's own names.
	NAME,
	/// The place's house number.
	HOUSENUMBER,
	/// The street of the place's address.
	STREET,
	/// The locality (neighbourhood) of the place's address.
	LOCALITY,
	/// The district (suburb) of the place's address.
	DISTRICT,
	/// The city of the place's address.
	CITY,
	/// The county of the place's address.
	COUNTY,
	/// The state of the place's address.
	STATE,
	/// The country of the place's address.
	COUNTRY,
	/// The postcode.
	POSTCODE,
	/// The country code.
	COUNTRYCODE,
	/// Other names that should find the place but are not shown.
	CONTEXT,
	/// A category path. Category terms start with [#CATEGORY_PREFIX].
	CATEGORY;

	/// Prefix that keeps category terms apart from text terms in the dictionary.
	static final String CATEGORY_PREFIX = "\u0001";

	/// Prefix of the capped prefix lists, see [IndexWriter].
	static final String PREFIX_LIST_PREFIX = "\u0002";

	final int bit = 1 << ordinal();

	/// All address fields: street up to country.
	static final int ADDRESS_MASK = STREET.bit | LOCALITY.bit | DISTRICT.bit | CITY.bit | COUNTY.bit | STATE.bit
			| COUNTRY.bit;

	/// Every text field.
	static final int TEXT_MASK = (CATEGORY.bit - 1);

	/// The field holding an address level.
	static Field of(AddressType type) {
		return switch (type) {
			case HOUSE, OTHER -> CONTEXT;
			case STREET -> STREET;
			case LOCALITY -> LOCALITY;
			case DISTRICT -> DISTRICT;
			case CITY -> CITY;
			case COUNTY -> COUNTY;
			case STATE -> STATE;
			case COUNTRY -> COUNTRY;
		};
	}

	/// Describes a mask for explanations, for example `name+city`.
	static String describe(int mask) {
		var sb = new StringBuilder();
		for (Field f : values()) {
			if ((mask & f.bit) != 0) {
				if (!sb.isEmpty()) {
					sb.append('+');
				}
				sb.append(f.name().toLowerCase(java.util.Locale.ROOT));
			}
		}
		return sb.toString();
	}

}
