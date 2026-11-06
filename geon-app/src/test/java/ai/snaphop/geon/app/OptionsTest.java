package ai.snaphop.geon.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OptionsTest {

	private static final Set<String> SWITCHES = Set.of("cors-any");

	private static final Set<String> VALUED = Set.of("listen-port", "data-dir");

	private static Options parse(List<String> args, Map<String, String> env) throws Options.UsageException {
		return Options.parse(args, SWITCHES, VALUED, Set.of("j"), env, new ArrayList<>());
	}

	enum Case {

		COMMAND_LINE(List.of("-listen-port", "9000"), Map.of(), "listen-port=9000 cors-any=false"),

		ENVIRONMENT(List.of(), Map.of("GEON_LISTEN_PORT", "9001", "GEON_CORS_ANY", "true"),
				"listen-port=9001 cors-any=true"),

		COMMAND_LINE_WINS(List.of("--listen-port", "9000"), Map.of("GEON_LISTEN_PORT", "9001"),
				"listen-port=9000 cors-any=false"),

		SWITCH_OFF(List.of(), Map.of("GEON_CORS_ANY", "false"), "listen-port=null cors-any=false"),

		BLANK_IS_UNSET(List.of(), Map.of("GEON_LISTEN_PORT", " "), "listen-port=null cors-any=false");

		final List<String> args;

		final Map<String, String> env;

		final String expected;

		Case(List<String> args, Map<String, String> env, String expected) {
			this.args = args;
			this.env = env;
			this.expected = expected;
		}

	}

	@ParameterizedTest
	@EnumSource(Case.class)
	void readsCommandLineThenEnvironment(Case c) throws Exception {
		var o = parse(c.args, c.env);
		assertEquals(c.expected, "listen-port=" + o.valueOrNull("listen-port") + " cors-any=" + o.has("cors-any"));
	}

	@Test
	void namesTheVariableInErrors() throws Exception {
		var o = parse(List.of(), Map.of("GEON_LISTEN_PORT", "abc"));
		var e = assertThrows(Options.UsageException.class, () -> o.integer("listen-port", 2322));
		assertEquals("GEON_LISTEN_PORT needs a whole number but got 'abc'.", e.getMessage());
	}

	@Test
	void rejectsSwitchValuesThatAreNotBooleans() {
		var e = assertThrows(Options.UsageException.class, () -> parse(List.of(), Map.of("GEON_CORS_ANY", "maybe")));
		assertEquals("GEON_CORS_ANY must be true or false but is 'maybe'.", e.getMessage());
	}

	@Test
	void rejectsABadImportBox() throws Exception {
		var o = Options.parse(List.of("-import-bbox", "-73.8,40.9,-66.8"), Set.of(), Set.of("import-bbox"), Set.of(),
				Map.of(), new ArrayList<>());
		var e = assertThrows(Options.UsageException.class, () -> Main.bbox(o));
		assertEquals("Invalid box '-73.8,40.9,-66.8' for -import-bbox. Use minLon,minLat,maxLon,maxLat in degrees,"
				+ " for example -73.8,40.9,-66.8,47.5.", e.getMessage());
	}

	@Test
	void readsAnImportBox() throws Exception {
		var o = Options.parse(List.of(), Set.of(), Set.of("import-bbox"), Set.of(),
				Map.of("GEON_IMPORT_BBOX", "-73.8, 40.9, -66.8, 47.5"), new ArrayList<>());
		assertEquals("BBox[minLon=-73.8, minLat=40.9, maxLon=-66.8, maxLat=47.5]", String.valueOf(Main.bbox(o)));
	}

	@Test
	void reportsUnknownGeonVariables() {
		var env = Map.of("GEON_LISTN_PORT", "1", "GEON_LISTEN_PORT", "2", "GEON_LOG_level", "debug",
				"GEON_JAVA_OPTS", "-Xmx1g", "GEON_IMPORT_MEMORY", "1g", "HOME", "/root");
		assertEquals(Set.of("GEON_LISTN_PORT"), Main.unknownVariables(env));
	}

}
