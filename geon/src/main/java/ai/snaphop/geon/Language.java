package ai.snaphop.geon;

import org.jspecify.annotations.Nullable;

/// The language results should be shown in.
///
/// @param preferred the language to use first, or [Place#DEFAULT_NAME] for the
///   names without a language.
/// @param fallback the language to use when a name is missing in `preferred`, or `null`.
public record Language(String preferred, @Nullable String fallback) {

	/// Show names without a language.
	public static final Language DEFAULT = new Language(Place.DEFAULT_NAME, null);

}
