package ai.snaphop.geon.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/// Imports with a one byte memory budget, so every posting goes through its own sorted
/// run on disk and the run merge, and expects exactly the responses of [PhotonApiTest].
class SmallMemoryImportTest {

	@TempDir
	static Path dir;

	static Fixture fixture;

	@BeforeAll
	static void start() throws Exception {
		fixture = Fixture.start("zedland", dir, 1);
	}

	@AfterAll
	static void stop() {
		fixture.close();
	}

	@ParameterizedTest
	@EnumSource(PhotonApiTest.Case.class)
	void respondsLikeAnInMemoryImport(PhotonApiTest.Case c) {
		var response = fixture.get(c.url);
		assertEquals(c.status + "\n" + c.body, response.status() + "\n" + Json.pretty(response.body()), c.url);
	}

}
