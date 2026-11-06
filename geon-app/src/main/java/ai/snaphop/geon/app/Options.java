package ai.snaphop.geon.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/// Command line options in Photon's style: `-name value` or `-name` for switches. A
/// double dash (`--name`) works too.
final class Options {

	private final Map<String, String> values = new LinkedHashMap<>();

	private final List<String> positional = new ArrayList<>();

	private Options() {
	}

	/// Parses arguments.
	/// @param switches options that take no value.
	/// @param valued options that take a value.
	/// @param ignored Photon options that have no meaning for Geon; they are accepted
	///   with a warning so existing Photon scripts keep working.
	static Options parse(List<String> args, Set<String> switches, Set<String> valued, Set<String> ignored,
			List<String> warnings) throws UsageException {
		var o = new Options();
		for (int i = 0; i < args.size(); i++) {
			String arg = args.get(i);
			if (!arg.startsWith("-") || arg.equals("-")) {
				o.positional.add(arg);
				continue;
			}
			String name = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);
			if (switches.contains(name)) {
				o.values.put(name, "true");
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
					o.values.put(name, value);
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
		return o;
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

	int integer(String name, int defaultValue) throws UsageException {
		String v = values.get(name);
		if (v == null) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(v);
		}
		catch (NumberFormatException e) {
			throw new UsageException("Option -" + name + " needs a whole number but got '" + v + "'.");
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

	/// A problem with the command line. The message is shown to the user as is.
	static final class UsageException extends Exception {

		private static final long serialVersionUID = 1L;

		UsageException(String message) {
			super(message);
		}

	}

}
