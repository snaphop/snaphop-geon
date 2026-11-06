package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsTest {

	@Test
	void countsRequestsByPathAndStatus(@TempDir Path dir) throws Exception {
		try (var fixture = Fixture.start("zedland", dir)) {
			fixture.get("/api?q=springfield");
			fixture.get("/api?q=springfield");
			fixture.get("/api");
			fixture.get("/scan/for/admin");
			var metrics = fixture.get("/metrics");
			assertEquals(200, metrics.status());
			// Latency buckets vary from run to run; everything else is exact.
			String stable = Arrays.stream(metrics.body().split("\n"))
				.filter(l -> !l.startsWith("geon_http_request_seconds") || l.contains("_count"))
				.collect(Collectors.joining("\n", "", "\n"));
			assertEquals("""
					# HELP geon_http_requests_total HTTP requests by path and status.
					# TYPE geon_http_requests_total counter
					geon_http_requests_total{path="/api",status="200"} 2
					geon_http_requests_total{path="/api",status="400"} 1
					geon_http_requests_total{path="other",status="404"} 1
					# HELP geon_http_request_seconds HTTP request latency by path.
					# TYPE geon_http_request_seconds histogram
					geon_http_request_seconds_count{path="/api"} 3
					geon_http_request_seconds_count{path="other"} 1
					# HELP geon_index_places Places in the index.
					# TYPE geon_index_places gauge
					geon_index_places 11.0
					""", stable);
		}
	}

}
