package ai.snaphop.geon.logging;

import ai.snaphop.geon.GeonVersion;
import io.jstach.rainbowgum.LogConfig;
import io.jstach.rainbowgum.keyvalues.contributor.DefaultKeyValues;
import io.jstach.rainbowgum.spi.RainbowGumServiceProvider;

/// Adds the version and commit of this build to every log event as default key
/// values, so a line can be traced to the release that wrote it. Any other source of
/// key values wins over these.
///
/// Rainbow Gum finds this class with [java.util.ServiceLoader].
public final class VersionKeyValues implements RainbowGumServiceProvider.Configurator {

	/// The key the version goes under.
	public static final String VERSION_KEY = "geon.version";

	/// The key the commit goes under.
	public static final String COMMIT_KEY = "git.commit";

	/// The [java.util.ServiceLoader] uses this.
	public VersionKeyValues() {
	}

	@Override
	public boolean configure(LogConfig config, Pass pass) {
		var values = DefaultKeyValues.of(config.serviceRegistry());
		values.put(VERSION_KEY, GeonVersion.VERSION);
		values.put(COMMIT_KEY, GeonVersion.COMMIT);
		return true;
	}

}
