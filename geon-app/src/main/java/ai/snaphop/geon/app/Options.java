package ai.snaphop.geon.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/// Command line options in Photon's style, `-name value` or `-name` for switches (a
/// double dash works too), with environment variables as defaults.
///
/// Every option `-name` can also be given as the environment variable `GEON_NAME`:
/// upper case, with `-` written as `_`. So `-listen-port 2322` and
/// `GEON_LISTEN_PORT=2322` are the same. The command line wins. A switch is on when
/// its variable is `true`, `yes` or `1`.
final class Options {

	/// The prefix of the environment variables that hold options.
	static final String ENV_PREFIX = "GEON_";

	private final Map<String, String> values = new LinkedHashMap<>();

	/// Where a value came from, for error messages: `-name` or the variable.
	private final Map<String, String> sources = new HashMap<>();

	private final List<String> positional = new ArrayList<>();

	private Options() {
	}

	/// The environment variable of an option: `listen-port` gives `GEON_LISTEN_PORT`.
	static String variable(String option) {
		return ENV_PREFIX + option.toUpperCase(Locale.ROOT).replace('-', '_');
	}

	/// Parses arguments, then fills in options that were not given from the environment.
	/// @param switches options that take no value.
	/// @param valued options that take a value.
	/// @param ignored Photon options that have no meaning for Geon; they are accepted
	///   with a warning so existing Photon scripts keep working.
	/// @param env the environment variables.
	static Options parse(List<String> args, Set<String> switches, Set<String> valued, Set<String> ignored,
			Map<String, String> env, List<String> warnings) throws UsageException {
		var o = new Options();
		for (int i = 0; i < args.size(); i++) {
			String arg = args.get(i);
			if (!arg.startsWith("-") || arg.equals("-")) {
				o.positional.add(arg);
				continue;
			}
			String name = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);
			if (switches.contains(name)) {
				o.put(name, "true", "-" + name);
			}
			else if (valued.contains(name) || ignored.contains(name)) {
				if (i + 1 >= args.size()) {
					throw new UsageException("Option -" + name + " needs a value.");
				}
				String value = args.get(++i);
				if (ignored.contains(name)) {
					warnings.add("Ignoring Photon option -" + name + " " + value + ": Geon does not use it.");
				}
				else {
					o.put(name, value, "-" + name);
				}
			}
			else {
				var known = new ArrayList<String>();
				known.addAll(switches);
				known.addAll(valued);
				known.sort(null);
				throw new UsageException("Unknown option '" + arg + "'. Known options: -" + String.join(", -", known)
						+ ".");
			}
		}
		for (String name : valued) {
			String value = env.get(variable(name));
			if (!o.values.containsKey(name) && value != null && !value.isBlank()) {
				o.put(name, value.strip(), variable(name));
			}
		}
		for (String name : switches) {
			String value = env.get(variable(name));
			if (!o.values.containsKey(name) && value != null) {
				String v = value.strip().toLowerCase(Locale.ROOT);
				if (v.equals("true") || v.equals("yes") || v.equals("1")) {
					o.put(name, "true", variable(name));
				}
				else if (!v.isEmpty() && !v.equals("false") && !v.equals("no") && !v.equals("0")) {
					throw new UsageException(variable(name) + " must be true or false but is '" + value + "'.");
				}
			}
		}
		return o;
	}

	private void put(String name, String value, String source) {
		values.put(name, value);
		sources.put(name, source);
	}

	@Nullable String valueOrNull(String name) {
		return values.get(name);
	}

	String value(String name, String defaultValue) {
		return values.getOrDefault(name, defaultValue);
	}

	boolean has(String name) {
		return values.containsKey(name);
	}

	/// How to name an option's value in an error: `-name` or its variable.
	String source(String name) {
		return sources.getOrDefault(name, "-" + name);
	}

	int integer(String name, int defaultValue) throws UsageException {
		String v = values.get(name);
		if (v == null) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(v);
		}
		catch (NumberFormatException e) {
			throw new UsageException(source(name) + " needs a whole number but got '" + v + "'.");
		}
	}

	List<String> list(String name) {
		String v = values.get(name);
		if (v == null) {
			return List.of();
		}
		return Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
	}

	List<String> positional() {
		return positional;
	}

	/// A problem with the command line or the environment. The message is shown to the
	/// user as is.
	static final class UsageException extends Exception {

		private static final long serialVersionUID = 1L;

		UsageException(String message) {
			super(message);
		}

	}

}
