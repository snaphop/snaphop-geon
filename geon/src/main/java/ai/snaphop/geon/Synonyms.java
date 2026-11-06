package ai.snaphop.geon;

import java.util.List;
import java.util.Set;

/// Query time vocabulary: words that may replace each other, and words that name the
/// type of a place.
///
/// Both apply to every query word. The last word, which may still be incomplete,
/// matches them only when it is complete.
///
/// @param groups sets of single words that may replace each other, for example
///   `{first, 1st}`.
/// @param classifications words that describe OSM tags.
public record Synonyms(List<Set<String>> groups, List<ClassificationTerm> classifications) {

	/// No synonyms and no classification terms.
	public static final Synonyms NONE = new Synonyms(List.of(), List.of());

	/// Copies the lists.
	public Synonyms {
		groups = groups.stream().map(Set::copyOf).toList();
		classifications = List.copyOf(classifications);
	}

	/// Whether there is nothing to apply.
	/// @return `true` if empty.
	public boolean isEmpty() {
		return groups.isEmpty() && classifications.isEmpty();
	}

}
