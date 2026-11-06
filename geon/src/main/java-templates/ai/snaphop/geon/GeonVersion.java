package ai.snaphop.geon;

/// What this build of Geon is. Maven fills these in: the templating plugin copies
/// `src/main/java-templates` with `${…}` replaced.
public final class GeonVersion {

	private GeonVersion() {
	}

	/// The Maven version, for example `0.1.0-SNAPSHOT`.
	public static final String VERSION = "${project.version}";

	/// The commit built, cut to 12 characters, or `unknown` if the build could not ask
	/// git.
	public static final String COMMIT = "${buildNumber}";

}
