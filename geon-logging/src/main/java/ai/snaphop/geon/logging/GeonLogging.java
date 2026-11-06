package ai.snaphop.geon.logging;

import io.jstach.rainbowgum.RainbowGum;

/// Starts logging for the `geon` command.
public final class GeonLogging {

	private GeonLogging() {
	}

	/// Starts Rainbow Gum, configured from `GEON_LOG_*` environment variables and
	/// system properties. Call it first thing in `main`.
	///
	/// The native image is built with `logging.systemlogger.initialize=false`, so the
	/// `System.Logger` finder never starts Rainbow Gum while the image is built; this
	/// starts it at run time instead.
	public static void start() {
		RainbowGum.of();
	}

}
