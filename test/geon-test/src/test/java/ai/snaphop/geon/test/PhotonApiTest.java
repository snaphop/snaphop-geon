package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/// End to end tests of the Photon compatible API over the `zedland` fixture: the dump
/// is imported, indexed and served over HTTP exactly as `geon import` and
/// `geon serve` do.
///
/// Error bodies whose description starts with "Photon" were checked against
/// Photon 1.3.0 and must stay identical. Result bodies document Geon's ranking; when
/// ranking changes on purpose, update them.
class PhotonApiTest {

	@TempDir
	static Path dir;

	static Fixture fixture;

	@BeforeAll
	static void start() throws Exception {
		fixture = Fixture.start("zedland", dir);
	}

	@AfterAll
	static void stop() {
		fixture.close();
	}

	@ParameterizedTest
	@EnumSource(Case.class)
	void respondsAsExpected(Case c) {
		var response = fixture.get(c.url);
		assertEquals(c.status + "\n" + c.body, response.status() + "\n" + Json.pretty(response.body()), c.url);
	}

	enum Case {

		/// Places named exactly like the query come first, the bigger place before the smaller.
		SEARCH_EXACT_NAME("/api?q=springfield&limit=4", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 200,
				        "osm_key": "place",
				        "osm_value": "city",
				        "type": "city",
				        "name": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0, 50.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 900,
				        "osm_key": "place",
				        "osm_value": "hamlet",
				        "type": "locality",
				        "name": "Springfield",
				        "state": "Lower Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [11.0, 51.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "R",
				        "osm_id": 300,
				        "osm_key": "place",
				        "osm_value": "suburb",
				        "type": "district",
				        "name": "Springfield Heights",
				        "city": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ",
				        "extent": [10.0, 50.02, 10.02, 50.0]
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.01, 50.01]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 1000,
				        "osm_key": "waterway",
				        "osm_value": "river",
				        "type": "other",
				        "name": "Springfield River",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ",
				        "extent": [9.9, 50.3, 10.3, 49.9]
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.1, 50.1]
				      }
				    }
				  ]
				}
				"""),

		/// The last word may be incomplete while the user types.
		SEARCH_PREFIX("/api?q=springf&limit=3", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 200,
				        "osm_key": "place",
				        "osm_value": "city",
				        "type": "city",
				        "name": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0, 50.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 900,
				        "osm_key": "place",
				        "osm_value": "hamlet",
				        "type": "locality",
				        "name": "Springfield",
				        "state": "Lower Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [11.0, 51.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "R",
				        "osm_id": 300,
				        "osm_key": "place",
				        "osm_value": "suburb",
				        "type": "district",
				        "name": "Springfield Heights",
				        "city": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ",
				        "extent": [10.0, 50.02, 10.02, 50.0]
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.01, 50.01]
				      }
				    }
				  ]
				}
				"""),

		/// A typo still finds the place when nothing matches exactly.
		SEARCH_TYPO("/api?q=sprngfield&limit=2", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 200,
				        "osm_key": "place",
				        "osm_value": "city",
				        "type": "city",
				        "name": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0, 50.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 900,
				        "osm_key": "place",
				        "osm_value": "hamlet",
				        "type": "locality",
				        "name": "Springfield",
				        "state": "Lower Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [11.0, 51.0]
				      }
				    }
				  ]
				}
				"""),

		/// A query with a house number prefers the address with that number.
		SEARCH_ADDRESS("/api?q=main street 12&limit=2", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 600,
				        "osm_key": "building",
				        "osm_value": "house",
				        "type": "house",
				        "housenumber": "12",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0012, 50.0012]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 400,
				        "osm_key": "highway",
				        "osm_value": "residential",
				        "type": "street",
				        "name": "Main Street",
				        "district": "Springfield Heights",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.001, 50.001]
				      }
				    }
				  ]
				}
				"""),

		/// An incomplete last word may match the address, here the city.
		SEARCH_ADDRESS_TYPING("/api?q=main street sp&limit=1", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 400,
				        "osm_key": "highway",
				        "osm_value": "residential",
				        "type": "street",
				        "name": "Main Street",
				        "district": "Springfield Heights",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.001, 50.001]
				      }
				    }
				  ]
				}
				"""),

		/// Accents and case do not matter; extra tags are returned.
		SEARCH_FOLDED("/api?q=cafe zurich", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 700,
				        "osm_key": "amenity",
				        "osm_value": "cafe",
				        "type": "house",
				        "housenumber": "3",
				        "name": "Café Zürich",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ",
				        "extra": {
				          "website": "https://cafe.example",
				          "cuisine": "coffee;cake",
				          "outdoor_seating": "yes"
				        }
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0015, 50.0015]
				      }
				    }
				  ]
				}
				"""),

		/// lang picks the translated name for matching and display.
		SEARCH_LANGUAGE("/api?q=springfeld&lang=de&limit=1", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 200,
				        "osm_key": "place",
				        "osm_value": "city",
				        "type": "city",
				        "name": "Springfeld",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0, 50.0]
				      }
				    }
				  ]
				}
				"""),

		/// A name without the requested translation shows the local name.
		SEARCH_LANGUAGE_FALLBACK("/api?q=moon hotel&lang=en", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 1100,
				        "osm_key": "tourism",
				        "osm_value": "hotel",
				        "type": "house",
				        "housenumber": "20",
				        "name": "Moon Hotel",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.003, 50.003]
				      }
				    }
				  ]
				}
				"""),

		/// Repeated ways of the same street collapse into one result.
		SEARCH_DEDUPE("/api?q=main street&limit=3", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 400,
				        "osm_key": "highway",
				        "osm_value": "residential",
				        "type": "street",
				        "name": "Main Street",
				        "district": "Springfield Heights",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.001, 50.001]
				      }
				    }
				  ]
				}
				"""),

		/// dedupe=0 keeps every way of the street.
		SEARCH_NO_DEDUPE("/api?q=main street&limit=3&dedupe=0", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 400,
				        "osm_key": "highway",
				        "osm_value": "residential",
				        "type": "street",
				        "name": "Main Street",
				        "district": "Springfield Heights",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.001, 50.001]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 401,
				        "osm_key": "highway",
				        "osm_value": "residential",
				        "type": "street",
				        "name": "Main Street",
				        "district": "Springfield Heights",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.002, 50.002]
				      }
				    }
				  ]
				}
				"""),

		/// Location bias prefers the small place near the given point.
		SEARCH_LOCATION_BIAS("/api?q=springfield&lat=51&lon=11&zoom=14&location_bias_scale=0.1&limit=2", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 900,
				        "osm_key": "place",
				        "osm_value": "hamlet",
				        "type": "locality",
				        "name": "Springfield",
				        "state": "Lower Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [11.0, 51.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 200,
				        "osm_key": "place",
				        "osm_value": "city",
				        "type": "city",
				        "name": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0, 50.0]
				      }
				    }
				  ]
				}
				"""),

		/// Without q, include alone lists the matching places by importance.
		SEARCH_CATEGORY_ONLY("/api?include=osm.place&limit=3", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "R",
				        "osm_id": 100,
				        "osm_key": "place",
				        "osm_value": "country",
				        "type": "country",
				        "name": "Zedland",
				        "country": "Zedland",
				        "countrycode": "ZZ",
				        "extent": [9.0, 52.0, 12.0, 49.0]
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.25, 50.25]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 200,
				        "osm_key": "place",
				        "osm_value": "city",
				        "type": "city",
				        "name": "Springfield",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0, 50.0]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 800,
				        "osm_key": "place",
				        "osm_value": "village",
				        "type": "city",
				        "name": "Shelbyville",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.5, 50.2]
				      }
				    }
				  ]
				}
				"""),

		/// layer restricts the address type.
		SEARCH_LAYER("/api?q=springfield&layer=locality", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 900,
				        "osm_key": "place",
				        "osm_value": "hamlet",
				        "type": "locality",
				        "name": "Springfield",
				        "state": "Lower Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [11.0, 51.0]
				      }
				    }
				  ]
				}
				"""),

		/// osm_tag restricts the OSM key.
		SEARCH_OSM_TAG("/api?q=springfield&osm_tag=waterway", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 1000,
				        "osm_key": "waterway",
				        "osm_value": "river",
				        "type": "other",
				        "name": "Springfield River",
				        "state": "Upper Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ",
				        "extent": [9.9, 50.3, 10.3, 49.9]
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.1, 50.1]
				      }
				    }
				  ]
				}
				"""),

		/// bbox restricts the area.
		SEARCH_BBOX("/api?q=springfield&bbox=10.9,50.9,11.1,51.1", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 900,
				        "osm_key": "place",
				        "osm_value": "hamlet",
				        "type": "locality",
				        "name": "Springfield",
				        "state": "Lower Zed",
				        "country": "Zedland",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [11.0, 51.0]
				      }
				    }
				  ]
				}
				"""),

		/// countrycode restricts the country.
		SEARCH_COUNTRYCODE("/api?q=springfield&countrycode=de", 200, """
				{
				  "type": "FeatureCollection",
				  "features": []
				}
				"""),

		/// Structured search finds the exact address.
		STRUCTURED_HOUSE("/structured?city=springfield&street=main street&housenumber=12", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 600,
				        "osm_key": "building",
				        "osm_value": "house",
				        "type": "house",
				        "housenumber": "12",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0012, 50.0012]
				      }
				    }
				  ]
				}
				"""),

		/// An unknown house number falls back to the street.
		STRUCTURED_FALLBACK_TO_STREET("/structured?city=springfield&street=main street&housenumber=99&limit=1", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "W",
				        "osm_id": 400,
				        "osm_key": "highway",
				        "osm_value": "residential",
				        "type": "street",
				        "name": "Main Street",
				        "district": "Springfield Heights",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.001, 50.001]
				      }
				    }
				  ]
				}
				"""),

		/// Reverse returns the nearest places first.
		REVERSE_NEAREST("/reverse?lat=50.0015&lon=10.0015&limit=2", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 700,
				        "osm_key": "amenity",
				        "osm_value": "cafe",
				        "type": "house",
				        "housenumber": "3",
				        "name": "Café Zürich",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ",
				        "extra": {
				          "website": "https://cafe.example",
				          "cuisine": "coffee;cake",
				          "outdoor_seating": "yes"
				        }
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0015, 50.0015]
				      }
				    },
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 600,
				        "osm_key": "building",
				        "osm_value": "house",
				        "type": "house",
				        "housenumber": "12",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.0012, 50.0012]
				      }
				    }
				  ]
				}
				"""),

		/// query_string_filter restricts reverse results.
		REVERSE_QUERY_STRING_FILTER("/reverse?lat=50.0015&lon=10.0015&query_string_filter=osm_key:tourism", 200, """
				{
				  "type": "FeatureCollection",
				  "features": [
				    {
				      "type": "Feature",
				      "properties": {
				        "osm_type": "N",
				        "osm_id": 1100,
				        "osm_key": "tourism",
				        "osm_value": "hotel",
				        "type": "house",
				        "housenumber": "20",
				        "name": "Hôtel Lune",
				        "street": "Main Street",
				        "city": "Springfield",
				        "country": "Zedland",
				        "postcode": "12345",
				        "countrycode": "ZZ"
				      },
				      "geometry": {
				        "type": "Point",
				        "coordinates": [10.003, 50.003]
				      }
				    }
				  ]
				}
				"""),

		/// Status reports the dump's data timestamp.
		STATUS("/status", 200, """
				{
				  "status": "Ok",
				  "import_date": "2026-01-02T03:04:05Z",
				  "version": "test",
				  "git_commit": "test"
				}
				"""),

		/// Photon: q or include is required.
		ERROR_MISSING_Q("/api", 400, """
				{
				  "message": "q parameter is required when no include categories are specified"
				}
				"""),

		/// Photon: unknown parameters are rejected with the allowed list.
		ERROR_UNKNOWN_PARAMETER("/api?q=x&bogus=1", 400, """
				{
				  "message": "Unknown query parameter 'bogus'.  Allowed parameters are: [include, location_bias_scale, debug, dedupe, bbox, countrycode, lon, zoom, layer, q, limit, osm_tag, suggest_addresses, exclude, geometry, lang, lat]"
				}
				"""),

		/// Photon: parse failures use Javalin's validation body.
		ERROR_LIMIT_NOT_A_NUMBER("/api?q=x&limit=abc", 400, """
				{
				  "limit": [
				    {
				      "message": "TYPE_CONVERSION_FAILED",
				      "args": {},
				      "value": "abc"
				    }
				  ]
				}
				"""),

		/// Photon: the parsed value is echoed.
		ERROR_LAT_OUT_OF_RANGE("/api?q=x&lat=100&lon=1", 400, """
				{
				  "lat": [
				    {
				      "message": "Invalid value for 'lat' parameter.",
				      "args": {},
				      "value": 100.0
				    }
				  ]
				}
				"""),

		/// Photon: lat needs lon.
		ERROR_LAT_WITHOUT_LON("/api?q=x&lat=42", 400, """
				{
				  "lat": [
				    {
				      "message": "Missing parameter 'lon'.",
				      "args": {},
				      "value": 42.0
				    }
				  ]
				}
				"""),

		/// Photon: languages are listed in Photon's order.
		ERROR_LANGUAGE("/api?q=x&lang=xx", 400, """
				{
				  "lang": [
				    {
				      "message": "Language is not supported. Supported are: default, de, en, it, fr",
				      "args": {},
				      "value": "xx"
				    }
				  ]
				}
				"""),

		/// Photon: layers are listed.
		ERROR_LAYER("/api?q=x&layer=foo", 400, """
				{
				  "layer": [
				    {
				      "message": "Unknown layer type. Available layers: [house, street, locality, district, city, county, state, country, other]",
				      "args": {},
				      "value": ["foo"]
				    }
				  ]
				}
				"""),

		/// Photon: a malformed osm_tag cannot be parsed.
		ERROR_OSM_TAG("/api?q=x&osm_tag=a:b:c", 400, """
				{
				  "osm_tag": [
				    {
				      "message": "TYPE_CONVERSION_FAILED",
				      "args": {},
				      "value": "a:b:c"
				    }
				  ]
				}
				"""),

		/// Photon: category patterns are checked.
		ERROR_INCLUDE("/api?q=x&include=foo", 400, """
				{
				  "include": [
				    {
				      "message": "Invalid category pattern in 'include'.",
				      "args": {},
				      "value": ["foo"]
				    }
				  ]
				}
				"""),

		/// Photon: a malformed bbox cannot be parsed.
		ERROR_BBOX("/api?q=x&bbox=1,2,3", 400, """
				{
				  "bbox": [
				    {
				      "message": "TYPE_CONVERSION_FAILED",
				      "args": {},
				      "value": "1,2,3"
				    }
				  ]
				}
				"""),

		/// Photon: geometries need an import with geometries.
		ERROR_GEOMETRY("/api?q=x&geometry=1", 400, """
				{
				  "geometry": [
				    {
				      "message": "Geometry output requested but not available in database.",
				      "args": {},
				      "value": true
				    }
				  ]
				}
				"""),

		/// Photon: structured search needs an address part.
		ERROR_STRUCTURED_EMPTY("/structured", 400, """
				{
				  "message": "at least one of the parameters [countrycode, state, county, city, postcode, district, housenumber, street] is required."
				}
				"""),

		/// Photon: reverse lists its own parameters.
		ERROR_REVERSE_UNKNOWN_PARAMETER("/reverse?lat=1&lon=1&foo=1", 400, """
				{
				  "message": "Unknown query parameter 'foo'.  Allowed parameters are: [include, debug, dedupe, query_string_filter, lon, layer, limit, osm_tag, distance_sort, exclude, geometry, lang, radius, lat]"
				}
				"""),

		/// Photon: radius must be a number.
		ERROR_REVERSE_RADIUS("/reverse?lat=1&lon=1&radius=abc", 400, """
				{
				  "radius": [
				    {
				      "message": "TYPE_CONVERSION_FAILED",
				      "args": {},
				      "value": "abc"
				    }
				  ]
				}
				"""),

		/// Geon: Photon fails with an internal error here; Geon names the missing parameter.
		ERROR_REVERSE_MISSING_LAT("/reverse", 400, """
				{
				  "lat": [
				    {
				      "message": "Missing parameter 'lat'.",
				      "args": {},
				      "value": null
				    }
				  ]
				}
				"""),

		/// Geon: Photon fails inside OpenSearch here; Geon rejects the radius.
		ERROR_REVERSE_NEGATIVE_RADIUS("/reverse?lat=1&lon=1&radius=-1", 400, """
				{
				  "radius": [
				    {
				      "message": "Invalid value for 'radius' parameter.",
				      "args": {},
				      "value": -1.0
				    }
				  ]
				}
				"""),

		/// Geon: unsupported Lucene syntax is rejected instead of ignored.
		ERROR_QUERY_STRING_FILTER("/reverse?lat=1&lon=1&query_string_filter=name:foo~2", 400, """
				{
				  "query_string_filter": [
				    {
				      "message": "Unsupported query_string_filter: wildcards, fuzzy and boost operators are not supported in 'name:foo~2'. Supported: field:value terms with AND, OR, NOT, - and parentheses.",
				      "args": {},
				      "value": "name:foo~2"
				    }
				  ]
				}
				"""),

		/// Unknown endpoints get a problem body like Photon's.
		ERROR_NOT_FOUND("/nothing", 404, """
				{
				  "title": "Endpoint GET /nothing not found",
				  "status": 404,
				  "type": "about:blank",
				  "details": {}
				}
				""");

		final String url;

		final int status;

		final String body;

		Case(String url, int status, String body) {
			this.url = url;
			this.status = status;
			this.body = body;
		}

	}

}
