package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.avaje.json.mapper.JsonMapper;

/// Sends the same requests to a real Photon and to Geon, both loaded with the
/// `zedland` fixture, and compares the responses as each case declares.
///
/// Cases that are not [Mode#SAME] say why on the constant. Run with
/// `./mvnw -Pphoton install`; `-Dphoton.jar=...` uses a local Photon jar instead of
/// downloading it.
class PhotonDifferentialTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TempDir
	static Path dir;

	static PhotonProcess photon;

	static Fixture geon;

	@BeforeAll
	static void start() throws Exception {
		photon = PhotonProcess.start("zedland", dir);
		geon = Fixture.start("zedland", dir.resolve("geon"));
	}

	@AfterAll
	static void stop() {
		if (photon != null) {
			photon.close();
		}
		if (geon != null) {
			geon.close();
		}
	}

	/// How strictly a case compares.
	enum Mode {

		/// Same status and same JSON.
		SAME,
		/// Same status and the same places in the same order.
		SAME_PLACES,
		/// Same status and the same places, in any order.
		SAME_SET,
		/// Same status and the same best place.
		SAME_TOP,
		/// Same status and a best place with the same name and type. For ties between
		/// equal places, such as two OSM ways of one street.
		SAME_TOP_NAME,
		/// Geon differs on purpose; the reason says why. Asserts that it still differs,
		/// so a case is revisited if Photon changes.
		DIFFERENT

	}

	enum Case {

		/// Geon ranks the hamlet named exactly Springfield above Springfield Heights and
		/// Springfield River; Photon ranks it last.
		SEARCH_EXACT_NAME(Mode.SAME_TOP, "/api?q=springfield&limit=4"),

		/// The same ranking choice as SEARCH_EXACT_NAME.
		SEARCH_PREFIX(Mode.SAME_TOP, "/api?q=springf&limit=3"),

		SEARCH_TYPO(Mode.SAME, "/api?q=sprngfield&limit=2"),

		/// The second result ties between two ways of Main Street.
		SEARCH_ADDRESS(Mode.SAME_TOP, "/api?q=main street 12&limit=2"),

		SEARCH_FOLDED(Mode.SAME, "/api?q=cafe zurich"),

		SEARCH_LANGUAGE(Mode.SAME, "/api?q=springfeld&lang=de&limit=1"),

		SEARCH_LANGUAGE_FALLBACK(Mode.SAME, "/api?q=moon hotel&lang=en"),

		/// Dedupe keeps one of two equal ways of Main Street; which one is a tie.
		SEARCH_DEDUPE(Mode.SAME_TOP_NAME, "/api?q=main street&limit=3"),

		/// The two equal ways of Main Street tie, so their order is arbitrary.
		SEARCH_NO_DEDUPE(Mode.SAME_SET, "/api?q=main street&limit=3&dedupe=0"),

		SEARCH_LOCATION_BIAS(Mode.SAME, "/api?q=springfield&lat=51&lon=11&zoom=14&location_bias_scale=0.1&limit=2"),

		SEARCH_CATEGORY_ONLY(Mode.SAME, "/api?include=osm.place&limit=3"),

		SEARCH_LAYER(Mode.SAME, "/api?q=springfield&layer=locality"),

		SEARCH_OSM_TAG(Mode.SAME, "/api?q=springfield&osm_tag=waterway"),

		SEARCH_BBOX(Mode.SAME, "/api?q=springfield&bbox=10.9,50.9,11.1,51.1"),

		SEARCH_COUNTRYCODE(Mode.SAME, "/api?q=springfield&countrycode=de"),

		STRUCTURED_HOUSE(Mode.SAME, "/structured?city=springfield&street=main street&housenumber=12"),

		/// Falls back to one of two equal ways of Main Street.
		STRUCTURED_FALLBACK_TO_STREET(Mode.SAME_TOP_NAME, "/structured?city=springfield&street=main street&housenumber=99&limit=1"),

		REVERSE_NEAREST(Mode.SAME, "/reverse?lat=50.0015&lon=10.0015&limit=2"),

		REVERSE_QUERY_STRING_FILTER(Mode.SAME, "/reverse?lat=50.0015&lon=10.0015&query_string_filter=osm_key:tourism"),

		ERROR_MISSING_Q(Mode.SAME, "/api"),

		ERROR_UNKNOWN_PARAMETER(Mode.SAME, "/api?q=x&bogus=1"),

		ERROR_LIMIT_NOT_A_NUMBER(Mode.SAME, "/api?q=x&limit=abc"),

		ERROR_LAT_OUT_OF_RANGE(Mode.SAME, "/api?q=x&lat=100&lon=1"),

		ERROR_LAT_WITHOUT_LON(Mode.SAME, "/api?q=x&lat=42"),

		ERROR_LANGUAGE(Mode.SAME, "/api?q=x&lang=xx"),

		ERROR_LAYER(Mode.SAME, "/api?q=x&layer=foo"),

		ERROR_OSM_TAG(Mode.SAME, "/api?q=x&osm_tag=a:b:c"),

		ERROR_INCLUDE(Mode.SAME, "/api?q=x&include=foo"),

		ERROR_BBOX(Mode.SAME, "/api?q=x&bbox=1,2,3"),

		ERROR_GEOMETRY(Mode.SAME, "/api?q=x&geometry=1"),

		ERROR_STRUCTURED_EMPTY(Mode.SAME, "/structured"),

		ERROR_REVERSE_UNKNOWN_PARAMETER(Mode.SAME, "/reverse?lat=1&lon=1&foo=1"),

		ERROR_REVERSE_RADIUS(Mode.SAME, "/reverse?lat=1&lon=1&radius=abc"),

		/// Photon's body links to its web framework's documentation; Geon's uses the RFC
		/// 9457 default type `about:blank`.
		ERROR_NOT_FOUND(Mode.DIFFERENT, "/nothing");

		final Mode mode;

		final String url;

		Case(Mode mode, String url) {
			this.mode = mode;
			this.url = url;
		}

	}

	@ParameterizedTest
	@EnumSource(Case.class)
	void matchesPhoton(Case c) throws Exception {
		var expected = photon.get(c.url);
		var actual = geon.get(c.url);
		String context = c.url + "\nphoton: " + expected + "\ngeon:   " + actual;
		switch (c.mode) {
			case SAME -> {
				assertEquals(expected.status(), actual.status(), context);
				assertEquals(JSON.fromJson(expected.body()), JSON.fromJson(actual.body()), context);
			}
			case SAME_PLACES -> {
				assertEquals(expected.status(), actual.status(), context);
				assertEquals(places(expected.body()), places(actual.body()), context);
			}
			case SAME_SET -> {
				assertEquals(expected.status(), actual.status(), context);
				assertEquals(new java.util.TreeSet<>(places(expected.body())), new java.util.TreeSet<>(places(actual.body())),
						context);
			}
			case SAME_TOP -> {
				assertEquals(expected.status(), actual.status(), context);
				assertEquals(places(expected.body()).stream().limit(1).toList(),
						places(actual.body()).stream().limit(1).toList(), context);
			}
			case SAME_TOP_NAME -> {
				assertEquals(expected.status(), actual.status(), context);
				assertEquals(names(expected.body()).stream().limit(1).toList(),
						names(actual.body()).stream().limit(1).toList(), context);
			}
			case DIFFERENT -> assertNotEquals(expected.toString(), actual.toString(), context);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> names(String body) {
		var features = (List<Map<String, Object>>) JSON.fromJsonObject(body).get("features");
		return features.stream().map(f -> {
			var p = (Map<String, Object>) f.get("properties");
			return p.get("name") + "|" + p.get("osm_key") + "=" + p.get("osm_value") + "#" + p.get("housenumber");
		}).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<String> places(String body) {
		var features = (List<Map<String, Object>>) JSON.fromJsonObject(body).get("features");
		return features.stream().map(f -> {
			var p = (Map<String, Object>) f.get("properties");
			return p.get("osm_type") + "" + p.get("osm_id") + "#" + p.get("housenumber");
		}).toList();
	}

}
