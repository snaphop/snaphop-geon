package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ai.snaphop.geon.photon.PhotonSynonyms;
import io.avaje.json.mapper.JsonMapper;

/// Photon and Geon serving the `zedland` fixture with the same synonym file must
/// return the same places.
class PhotonSynonymsDifferentialTest {

	private static final String FILE = """
			{
			  "search_synonyms": ["hotel,inn"],
			  "classification_terms": [
			    {"key": "tourism", "value": "hotel", "terms": ["lodging"]},
			    {"key": "waterway", "value": "river", "terms": ["Stream"]}
			  ]
			}
			""";

	@TempDir
	static Path dir;

	static PhotonProcess photon;

	static Fixture geon;

	@BeforeAll
	static void start() throws Exception {
		Path file = dir.resolve("synonyms.json");
		Files.writeString(file, FILE);
		photon = PhotonProcess.start("zedland", dir, List.of("-synonym-file", file.toString()));
		geon = Fixture.start("zedland", dir.resolve("geon"), 0, PhotonSynonyms.load(file));
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

	enum Case {

		SYNONYM("/api?q=inn lune"),

		CLASSIFICATION("/api?q=lodging lune"),

		CLASSIFICATION_FOLDED("/api?q=STREAM springfield"),

		CLASSIFICATION_ALONE("/api?q=lodging "),

		SYNONYM_LAST_WORD("/api?q=lune inn"),

		CLASSIFICATION_LAST_WORD("/api?q=lune lodging");

		final String url;

		Case(String url) {
			this.url = url;
		}

	}

	@ParameterizedTest
	@EnumSource(Case.class)
	void samePlacesAsPhoton(Case c) throws Exception {
		var expected = photon.get(c.url);
		var actual = geon.get(c.url);
		String context = c.url + "\nphoton: " + expected + "\ngeon:   " + actual;
		assertEquals(expected.status(), actual.status(), context);
		assertEquals(places(expected.body()), places(actual.body()), context);
	}

	@SuppressWarnings("unchecked")
	private static List<String> places(String body) {
		var features = (List<Map<String, Object>>) JsonMapper.builder().build().fromJsonObject(body).get("features");
		return features.stream().map(f -> {
			var p = (Map<String, Object>) f.get("properties");
			return p.get("osm_type") + "" + p.get("osm_id");
		}).toList();
	}

}
