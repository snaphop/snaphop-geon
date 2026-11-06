package ai.snaphop.geon.photon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.ClassificationTerm;
import ai.snaphop.geon.Synonyms;
import io.avaje.json.JsonException;
import io.avaje.json.JsonReader;

/// Reads Photon's synonym file (`-synonym-file`).
///
/// ```json
/// {
///   "search_synonyms": ["first,1st", "second,2nd"],
///   "classification_terms": [
///     {"key": "railway", "value": "station", "terms": ["station"]}
///   ]
/// }
/// ```
///
/// As in Photon, synonym terms must be single words, entries whose key or value is not
/// a valid category label are dropped, and classification terms of one character are
/// ignored.
public final class PhotonSynonyms {

	private static final Pattern LABEL = Pattern.compile("[a-zA-Z0-9_-]+");

	private PhotonSynonyms() {
	}

	/// Loads a synonym file.
	/// @param file the JSON file.
	/// @return the vocabulary.
	/// @throws IOException if the file cannot be read, is not valid JSON, or breaks
	///   Photon's rules; the message says which entry.
	public static Synonyms load(Path file) throws IOException {
		try (JsonReader r = PhotonError.JSON.reader(Files.readAllBytes(file))) {
			return read(r);
		}
		catch (JsonException e) {
			throw new IOException("Invalid synonym file " + file + ": " + e.getMessage(), e);
		}
		catch (IllegalArgumentException e) {
			throw new IOException("Invalid synonym file " + file + ": " + e.getMessage(), e);
		}
	}

	private static Synonyms read(JsonReader r) {
		var groups = new ArrayList<Set<String>>();
		var classifications = new ArrayList<ClassificationTerm>();
		r.beginObject();
		while (r.hasNextField()) {
			String field = r.nextField();
			if (r.isNullValue()) {
				continue;
			}
			switch (field) {
				case "search_synonyms" -> {
					r.beginArray();
					while (r.hasNextElement()) {
						String entry = r.readString();
						if (entry.matches(".*[ '-].*")) {
							throw new IllegalArgumentException(
									"Error in synonym entry '" + entry + "'. Terms must not contain spaces or hyphens.");
						}
						var group = new LinkedHashSet<String>();
						for (String term : entry.split(",")) {
							if (!term.isBlank()) {
								group.add(term.strip());
							}
						}
						if (group.size() > 1) {
							groups.add(group);
						}
					}
					r.endArray();
				}
				case "classification_terms" -> {
					r.beginArray();
					while (r.hasNextElement()) {
						ClassificationTerm term = classification(r);
						if (term != null) {
							classifications.add(term);
						}
					}
					r.endArray();
				}
				default -> r.skipValue();
			}
		}
		r.endObject();
		return new Synonyms(groups, classifications);
	}

	private static @Nullable ClassificationTerm classification(JsonReader r) {
		String key = null;
		String value = null;
		List<String> terms = new ArrayList<>();
		r.beginObject();
		while (r.hasNextField()) {
			String field = r.nextField();
			if (r.isNullValue()) {
				continue;
			}
			switch (field) {
				case "key" -> key = r.readString();
				case "value" -> value = r.readString();
				case "terms" -> {
					r.beginArray();
					while (r.hasNextElement()) {
						if (!r.isNullValue()) {
							terms.add(r.readString());
						}
					}
					r.endArray();
				}
				default -> r.skipValue();
			}
		}
		r.endObject();
		if (key == null || value == null || !LABEL.matcher(key).matches() || !LABEL.matcher(value).matches()) {
			return null;
		}
		var words = new LinkedHashSet<String>();
		for (String term : terms) {
			String norm = term.toLowerCase(Locale.ROOT).strip();
			if (norm.indexOf(' ') >= 0) {
				throw new IllegalArgumentException(
						"Syntax error in synonym file: only single word classification terms allowed.");
			}
			if (norm.length() > 1) {
				words.add(norm);
			}
		}
		return words.isEmpty() ? null : new ClassificationTerm(key, value, words);
	}

}
