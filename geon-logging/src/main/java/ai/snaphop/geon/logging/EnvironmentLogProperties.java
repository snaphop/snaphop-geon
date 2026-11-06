package ai.snaphop.geon.logging;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import io.jstach.rainbowgum.LogAlerts;
import io.jstach.rainbowgum.LogProperties;
import io.jstach.rainbowgum.LogReporter;
import io.jstach.rainbowgum.ServiceRegistry;
import io.jstach.rainbowgum.spi.RainbowGumServiceProvider;

/// Rainbow Gum properties from `GEON_LOG_*` environment variables, so logging is
/// configured like the rest of Geon: with the environment.
///
/// The Rainbow Gum property `logging.X` is read from `GEON_LOG_X` with each `.`
/// written as `_`. Case matters and is kept as is:
///
/// | Environment variable | Rainbow Gum property |
/// |---|---|
/// | `GEON_LOG_level=warning` | `logging.level` |
/// | `GEON_LOG_level_io_netty=info` | `logging.level.io.netty` |
/// | `GEON_LOG_appender_console_encoder_uri=ltsv` | `logging.appender.console.encoder.uri` |
///
/// A logger name that is awkward in a variable name goes in a group, whose value can
/// hold any logger names:
///
/// ```
/// GEON_LOG_groups=odd
/// GEON_LOG_group_odd=com.example.odd_name
/// GEON_LOG_level_odd=debug
/// ```
///
/// Java system properties (`-Dlogging.level=...`) win over these. The variables are
/// [listable][LogProperties.Listable], so Rainbow Gum reports one it never read, which
/// catches typos.
///
/// Rainbow Gum finds this class with [java.util.ServiceLoader].
public final class EnvironmentLogProperties implements RainbowGumServiceProvider.PropertiesProvider {

	/// What replaces `logging.` in a variable name.
	public static final String PREFIX = "GEON_LOG_";

	/// Rainbow Gum's order for environment variables: below system properties.
	static final int ORDER = 300;

	private final Map<String, String> environment;

	/// Reads the process environment. The [java.util.ServiceLoader] uses this.
	public EnvironmentLogProperties() {
		this(System.getenv());
	}

	/// Reads the given variables instead of the process environment.
	/// @param environment environment variables to copy and expose as properties.
	public EnvironmentLogProperties(Map<String, String> environment) {
		this.environment = Map.copyOf(environment);
	}

	@Override
	public List<LogProperties> provideProperties(ServiceRegistry registry, LogAlerts alerts) {
		return List.of(properties());
	}

	/// The properties, on their own.
	/// @return the logging properties backed by the copied environment.
	public LogProperties.Listable properties() {
		return new Variables(environment);
	}

	/// The environment variable that holds a Rainbow Gum property, for example
	/// `logging.level.io.netty` gives `GEON_LOG_level_io_netty`.
	/// @param property the Rainbow Gum property name.
	/// @return the environment variable name.
	public static String variable(String property) {
		String name = property.startsWith(LogProperties.ROOT_PREFIX)
				? property.substring(LogProperties.ROOT_PREFIX.length()) : property;
		return PREFIX + name.replace('.', '_');
	}

	/// The property a `GEON_LOG_*` variable stands for, reading each `_` as `.`. Only
	/// used to report unread variables; lookups go the other way.
	static String property(String variable) {
		return LogProperties.ROOT_PREFIX + variable.substring(PREFIX.length()).replace('_', '.');
	}

	private record Variables(Map<String, String> environment) implements LogProperties.Listable, LogReporter.Reportable {

		@Override
		public @Nullable String valueOrNull(String key) {
			return environment.get(variable(key));
		}

		@Override
		public Collection<String> keys() {
			var keys = new TreeSet<String>();
			for (String name : environment.keySet()) {
				if (name.startsWith(PREFIX) && name.length() > PREFIX.length()) {
					keys.add(property(name));
				}
			}
			return keys;
		}

		@Override
		public String description(String key) {
			return "environment variable " + variable(key);
		}

		@Override
		public int order() {
			return ORDER;
		}

		@Override
		public void report(Appendable out) throws IOException {
			out.append("GEON_LOG_* environment variables");
		}

	}

}
