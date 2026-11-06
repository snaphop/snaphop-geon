package ai.snaphop.geon.dump;

import java.util.List;

/// Which of a place's `extra` tags to keep on import.
///
/// Extra tags are returned with search results but are never searched.
///
/// @param all keep every extra tag.
/// @param keys keep only these tags when `all` is `false`. Empty keeps none.
public record ExtraTags(boolean all, List<String> keys) {

	/// Keep no extra tags. This is the default, as in Photon.
	public static final ExtraTags NONE = new ExtraTags(false, List.of());

	/// Keep every extra tag.
	public static final ExtraTags ALL = new ExtraTags(true, List.of());

	/// Copies the key list.
	public ExtraTags {
		keys = List.copyOf(keys);
	}

	/// Keep only the named tags.
	/// @param keys tag names such as `website` or `wikidata`.
	/// @return the setting.
	public static ExtraTags of(List<String> keys) {
		return keys.isEmpty() ? NONE : new ExtraTags(false, keys);
	}

	/// Parses Photon's `-extra-tags` value: `ALL`, or a comma separated list of tags.
	/// @param value the setting.
	/// @return the parsed setting.
	public static ExtraTags parse(String value) {
		if (value.strip().equals("ALL")) {
			return ALL;
		}
		return of(java.util.Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
	}

	boolean keepsAny() {
		return all || !keys.isEmpty();
	}

	boolean keeps(String key) {
		return all || keys.contains(key);
	}

}
