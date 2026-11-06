package ai.snaphop.geon;

import java.util.Set;

/// Words that describe places with an OSM tag, so the type of a place can be named in
/// a query even when its name does not say it.
///
/// With `railway=station` described by `station`, the query `Berlin station` finds the
/// station named "Berlin" as well as "Berlin Hauptbahnhof". It does not list stations:
/// some other word must still match the place's name.
///
/// @param key the OSM key, for example `railway`.
/// @param value the OSM value, for example `station`.
/// @param terms single words that describe such places.
public record ClassificationTerm(String key, String value, Set<String> terms) {

	/// Copies the terms.
	public ClassificationTerm {
		terms = Set.copyOf(terms);
	}

	/// The category places with this tag carry, for example `osm.railway.station`.
	/// @return the category path.
	public String category() {
		return "osm." + key + "." + value;
	}

}
