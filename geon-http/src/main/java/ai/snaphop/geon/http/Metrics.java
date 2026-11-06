package ai.snaphop.geon.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

/// Request metrics in the Prometheus text format. Thread safe.
///
/// ```
/// geon_http_requests_total{path="/api",status="200"}        counter
/// geon_http_request_seconds_bucket{path="/api",le="0.001"}  histogram
/// ```
///
/// plus any gauges added with [#gauge(String, String, DoubleSupplier)].
public final class Metrics {

	private static final double[] BUCKETS = { 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1,
			2.5 };

	private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

	private final Map<String, Histogram> latencies = new ConcurrentHashMap<>();

	private final List<Gauge> gauges = new ArrayList<>();

	private record Gauge(String name, String help, DoubleSupplier value) {
	}

	/// Creates empty metrics.
	public Metrics() {
	}

	private static final class Histogram {

		final AtomicLongArray buckets = new AtomicLongArray(BUCKETS.length);

		final LongAdder count = new LongAdder();

		final LongAdder nanos = new LongAdder();

		void record(long elapsed) {
			double seconds = elapsed / 1e9;
			for (int i = 0; i < BUCKETS.length; i++) {
				if (seconds <= BUCKETS[i]) {
					buckets.incrementAndGet(i);
				}
			}
			count.increment();
			nanos.add(elapsed);
		}

	}

	/// Adds a gauge, for example the number of places in the index.
	/// @param name the metric name, such as `geon_index_places`.
	/// @param help one line describing it.
	/// @param value read on every scrape.
	/// @return this.
	public synchronized Metrics gauge(String name, String help, DoubleSupplier value) {
		gauges.add(new Gauge(name, help, value));
		return this;
	}

	void record(String path, int status, long nanos) {
		counts.computeIfAbsent(path + "\u0000" + status, k -> new LongAdder()).increment();
		latencies.computeIfAbsent(path, k -> new Histogram()).record(nanos);
	}

	/// A handler that serves [#prometheus()].
	/// @return the handler.
	public HttpHandler handler() {
		return request -> HttpResponse.text(200, "text/plain; version=0.0.4; charset=utf-8", prometheus());
	}

	/// The current values in the Prometheus text exposition format.
	/// @return the text.
	public String prometheus() {
		var sb = new StringBuilder();
		sb.append("# HELP geon_http_requests_total HTTP requests by path and status.\n");
		sb.append("# TYPE geon_http_requests_total counter\n");
		counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
			String[] key = e.getKey().split("\u0000");
			sb.append("geon_http_requests_total{path=\"").append(key[0]).append("\",status=\"").append(key[1])
				.append("\"} ").append(e.getValue().sum()).append('\n');
		});
		sb.append("# HELP geon_http_request_seconds HTTP request latency by path.\n");
		sb.append("# TYPE geon_http_request_seconds histogram\n");
		latencies.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
			String path = e.getKey();
			Histogram h = e.getValue();
			for (int i = 0; i < BUCKETS.length; i++) {
				sb.append("geon_http_request_seconds_bucket{path=\"").append(path).append("\",le=\"")
					.append(BUCKETS[i]).append("\"} ").append(h.buckets.get(i)).append('\n');
			}
			sb.append("geon_http_request_seconds_bucket{path=\"").append(path).append("\",le=\"+Inf\"} ")
				.append(h.count.sum()).append('\n');
			sb.append("geon_http_request_seconds_sum{path=\"").append(path).append("\"} ")
				.append(String.format(Locale.ROOT, "%.6f", h.nanos.sum() / 1e9)).append('\n');
			sb.append("geon_http_request_seconds_count{path=\"").append(path).append("\"} ").append(h.count.sum())
				.append('\n');
		});
		List<Gauge> snapshot;
		synchronized (this) {
			snapshot = List.copyOf(gauges);
		}
		for (Gauge g : snapshot) {
			sb.append("# HELP ").append(g.name()).append(' ').append(g.help()).append('\n');
			sb.append("# TYPE ").append(g.name()).append(" gauge\n");
			sb.append(g.name()).append(' ').append(g.value().getAsDouble()).append('\n');
		}
		return sb.toString();
	}

}
