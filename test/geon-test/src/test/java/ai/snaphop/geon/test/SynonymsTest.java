package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
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

/// Synonyms and classification terms loaded from a Photon synonym file.
class SynonymsTest {

	private static final String FILE = """
			{
			  "search_synonyms": ["hotel,inn"],
			  "classification_terms": [
			    {"key": "tourism", "value": "hotel", "terms": ["lodging"]},
			    {"key": "waterway", "value": "river", "terms": ["Stream", "x"]},
			    {"key": "bad key", "value": "x", "terms": ["ignored"]}
			  ]
			}
			""";

	@TempDir
	static Path dir;

	static Fixture fixture;

	@BeforeAll
	static void start() throws Exception {
		Path file = dir.resolve("synonyms.json");
		Files.writeString(file, FILE);
		fixture = Fixture.start("zedland", dir, 0, PhotonSynonyms.load(file));
	}

	@AfterAll
	static void stop() {
		fixture.close();
	}

	enum Case {

		/// "inn" may replace "hotel".
		SYNONYM("/api?q=inn lune", """
				Hôtel Lune (tourism=hotel)
				"""),

		/// A classification term names the type; "lune" names the place.
		CLASSIFICATION("/api?q=lodging lune", """
				Hôtel Lune (tourism=hotel)
				"""),

		/// Classification terms are case folded.
		CLASSIFICATION_FOLDED("/api?q=STREAM springfield", """
				Springfield River (waterway=river)
				"""),

		/// A classification term alone does not list places of that type.
		CLASSIFICATION_ALONE("/api?q=lodging ", """
				"""),

		/// The last word matches its synonyms when it is complete, as in Photon.
		SYNONYM_LAST_WORD("/api?q=lune inn", """
				Hôtel Lune (tourism=hotel)
				"""),

		/// So does a classification term.
		CLASSIFICATION_LAST_WORD("/api?q=lune lodging", """
				Hôtel Lune (tourism=hotel)
				""");

		final String url;

		final String expected;

		Case(String url, String expected) {
			this.url = url;
			this.expected = expected;
		}

	}

	@ParameterizedTest
	@EnumSource(Case.class)
	void appliesVocabulary(Case c) {
		var response = fixture.get(c.url);
		assertEquals(200, response.status(), response.body());
		assertEquals(c.expected, names(response.body()), c.url);
	}

	@SuppressWarnings("unchecked")
	private static String names(String body) {
		var features = (List<Map<String, Object>>) JsonMapper.builder().build().fromJsonObject(body).get("features");
		var sb = new StringBuilder();
		for (var f : features) {
			var p = (Map<String, Object>) f.get("properties");
			sb.append(p.get("name")).append(" (").append(p.get("osm_key")).append('=').append(p.get("osm_value")).append(")\n");
		}
		return sb.toString();
	}

	enum Invalid {

		MULTI_WORD_SYNONYM("""
				{"search_synonyms": ["saint,st john"]}
				""", "Error in synonym entry 'saint,st john'. Terms must not contain spaces or hyphens."),

		MULTI_WORD_CLASSIFICATION("""
				{"classification_terms": [{"key": "railway", "value": "station", "terms": ["train station"]}]}
				""", "Syntax error in synonym file: only single word classification terms allowed.");

		final String json;

		final String message;

		Invalid(String json, String message) {
			this.json = json;
			this.message = message;
		}

	}

	@ParameterizedTest
	@EnumSource(Invalid.class)
	void rejectsWhatPhotonRejects(Invalid c) throws IOException {
		Path file = dir.resolve(c.name() + ".json");
		Files.writeString(file, c.json);
		var e = assertThrows(IOException.class, () -> PhotonSynonyms.load(file));
		assertEquals("Invalid synonym file " + file + ": " + c.message, e.getMessage());
	}

}
