package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// JSON that avaje-json 3.16 got wrong, read from a dump and written back by the
/// Photon API: a place's geometry far longer than its 50,000 character limit on a
/// string or raw value, non-ASCII extra tags written as raw JSON, and a dump big
/// enough that strings end on every byte of the reader's buffer.
class LargeJsonTest {

	@TempDir
	Path dir;

	@Test
	void longGeometriesAndNonAsciiExtraTagsComeBackWhole() throws Exception {
		int points = 5000;
		int fillers = 2000;
		var dump = new StringBuilder();
		dump.append("""
				{"type":"NominatimDumpFile","content":{"version":"0.1.0","generator":"geon-test","data_timestamp":"2026-01-02T03:04:05+00:00","features":{"sorted_by_country":true,"has_addresslines":false}}}
				{"type":"CountryInfo","content":[{"country_code":"zz","name":{"name":"Zedland"}}]}
				""");
		// Names of every length, so strings and arrays close at every buffer offset.
		for (int i = 0; i < fillers; i++) {
			dump.append("{\"type\":\"Place\",\"content\":[{\"place_id\":").append(1000 + i)
				.append(",\"object_type\":\"N\",\"object_id\":").append(1000 + i)
				.append(",\"osm_key\":\"amenity\",\"osm_value\":\"bench\",\"categories\":[\"osm.amenity.bench\"],")
				.append("\"address_type\":\"house\",\"importance\":0.01,\"name\":{\"name\":\"Bench ")
				.append("x".repeat(i % 97)).append("\"},\"address\":{},\"country_code\":\"zz\",\"centroid\":[10.0,50.0]}]}\n");
		}
		var coordinates = new StringBuilder();
		for (int i = 0; i < points; i++) {
			coordinates.append(i == 0 ? "" : ",").append('[').append(lon(i)).append(',').append(lat(i)).append(']');
		}
		dump.append("{\"type\":\"Place\",\"content\":[{\"place_id\":9,\"object_type\":\"W\",\"object_id\":900,")
			.append("\"osm_key\":\"waterway\",\"osm_value\":\"river\",\"categories\":[\"osm.waterway.river\"],")
			.append("\"address_type\":\"street\",\"importance\":0.2,\"name\":{\"name\":\"Großer Weiherbach\"},")
			.append("\"extra\":{\"name:ja\":\"大池川\",\"note\":\"Café “quoted” — ünïcode\"},")
			.append("\"address\":{},\"country_code\":\"zz\",\"centroid\":[10.25,50.25],")
			.append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[").append(coordinates).append("]}}]}\n");
		assertTrue(coordinates.length() > 50_000, "the geometry is over avaje's limit");

		try (var fixture = Fixture.startWithGeometries(dump.toString().getBytes(StandardCharsets.UTF_8), dir)) {
			assertTrue(fixture.get("/metrics").body().contains("geon_index_places " + (fillers + 1)),
					"every place is imported");

			var response = fixture.get("/api?q=weiherbach&geometry=true&limit=1");
			assertEquals(200, response.status(), response.body());
			var feature = com.snaphop.jsonl.Json
				.map(com.snaphop.jsonl.Json.list(com.snaphop.jsonl.Json.map(com.snaphop.jsonl.Json.read(response.body()))
					.get("features")).getFirst());
			var properties = com.snaphop.jsonl.Json.map(feature.get("properties"));
			assertEquals("Großer Weiherbach", properties.get("name"));
			assertEquals(Map.of("name:ja", "大池川", "note", "Café “quoted” — ünïcode"), properties.get("extra"));
			var line = com.snaphop.jsonl.Json.list(com.snaphop.jsonl.Json.map(feature.get("geometry")).get("coordinates"));
			assertEquals(points, line.size());
			for (int i = 0; i < points; i++) {
				assertEquals(List.of(lon(i), lat(i)), line.get(i), "point " + i);
			}
		}
	}

	private static double lon(int i) {
		return 10 + i / 10_000.0;
	}

	private static double lat(int i) {
		return 50 + i / 7_000.0;
	}

}
