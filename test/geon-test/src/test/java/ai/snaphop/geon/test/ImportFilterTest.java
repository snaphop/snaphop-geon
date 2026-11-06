package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import ai.snaphop.geon.BBox;
import ai.snaphop.geon.Place;
import ai.snaphop.geon.dump.DumpReader;

class ImportFilterTest {

	@Test
	void importsOnlyPlacesInsideTheBox() throws Exception {
		var names = new ArrayList<String>();
		DumpSummaryCheck summary;
		try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/zedland.jsonl"))) {
			var s = DumpReader.builder()
				.bbox(new BBox(10.9, 50.9, 11.1, 51.1))
				.build()
				.read(in, p -> names.add(p.names().getOrDefault(Place.DEFAULT_NAME, "#" + p.houseNumber())));
			summary = new DumpSummaryCheck(s.placeCount(), s.skippedCount());
		}
		// Only the hamlet of Springfield is in the box; the ten other places are skipped.
		assertEquals("[Springfield] DumpSummaryCheck[places=1, skipped=10]", names + " " + summary);
	}

	record DumpSummaryCheck(long places, long skipped) {
	}

}
