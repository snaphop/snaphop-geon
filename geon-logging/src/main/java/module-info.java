/// Logging for the `geon` command: Rainbow Gum configured from `GEON_LOG_*`
/// environment variables ([ai.snaphop.geon.logging.EnvironmentLogProperties]), with
/// the version and commit on every log event
/// ([ai.snaphop.geon.logging.VersionKeyValues]).
///
/// Geon's code logs through `System.Logger` and never uses Rainbow Gum's API; this
/// module is the only place that does. [ai.snaphop.geon.logging.GeonLogging#start()]
/// is all the application calls.
module ai.snaphop.geon.logging {
	exports ai.snaphop.geon.logging;

	requires ai.snaphop.geon;
	requires io.jstach.rainbowgum;
	requires io.jstach.rainbowgum.keyvalues.contributor;
	requires io.jstach.rainbowgum.jdk;
	requires static org.jspecify;

	provides io.jstach.rainbowgum.spi.RainbowGumServiceProvider with
			ai.snaphop.geon.logging.EnvironmentLogProperties,
			ai.snaphop.geon.logging.VersionKeyValues;
}
