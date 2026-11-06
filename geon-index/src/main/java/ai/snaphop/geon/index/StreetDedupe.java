package ai.snaphop.geon.index;

import java.util.HashSet;
import java.util.Set;

import ai.snaphop.geon.Place;

/// Removes repeated streets: long streets are split into many OSM ways that all
/// carry the same name.
final class StreetDedupe {

	private final Set<String> seen = new HashSet<>();

	boolean keep(Place p) {
		if (!p.osmKey().equals("highway")) {
			return true;
		}
		String name = p.names().get(Place.DEFAULT_NAME);
		if (name == null) {
			return true;
		}
		String postcode = p.postcode() == null ? "" : p.postcode();
		int space = postcode.indexOf(' ');
		if (space > 0) {
			// Compare postcode districts where full postcodes split streets: NL 1234 AB, GB SW1A 1AA.
			if ("NL".equals(p.countryCode())) {
				postcode = postcode.substring(0, space);
			}
			else if ("GB".equals(p.countryCode()) && postcode.length() > space + 2) {
				postcode = postcode.substring(0, space + 2);
			}
		}
		return seen.add(p.osmValue() + ':' + postcode + ':' + name + ':' + p.countryCode());
	}

}
