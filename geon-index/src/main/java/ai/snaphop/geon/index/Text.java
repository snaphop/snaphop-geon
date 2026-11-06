package ai.snaphop.geon.index;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// Text normalization and tokenization, shared by indexing and querying so both sides
/// always agree.
///
/// ## Folding
///
/// Text is decomposed (NFKD), combining marks are dropped, and the result is lower
/// cased. Letters that do not decompose are mapped explicitly: `ß` to `ss`, `ø` to
/// `o`, `æ` to `ae`, `ł` to `l` and a few more. So `Zürich`, `ZURICH` and `zurich` all
/// fold to `zurich`.
///
/// ## Tokens
///
/// A token is a run of letters and digits. Some characters join tokens instead of
/// separating them: apostrophes, hyphens, dots, slashes and the Catalan middle dot.
/// Joined runs are indexed both as their parts and as one concatenated token, so
/// `Saint-Denis` is found by `saint`, `denis` and `saintdenis`, and `l'Isard` by
/// `isard` and `lisard`.
final class Text {

	private Text() {
	}

	/// Folds a string as described in the class documentation, keeping all other
	/// characters.
	static String fold(String s) {
		boolean ascii = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x80 || (c >= 'A' && c <= 'Z')) {
				ascii = false;
				break;
			}
		}
		if (ascii) {
			return s;
		}
		String decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD);
		var out = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length();) {
			int cp = decomposed.codePointAt(i);
			i += Character.charCount(cp);
			int type = Character.getType(cp);
			if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
					|| type == Character.COMBINING_SPACING_MARK) {
				continue;
			}
			switch (cp) {
				case 'ß', 'ẞ' -> out.append("ss");
				case 'ø', 'Ø' -> out.append('o');
				case 'æ', 'Æ' -> out.append("ae");
				case 'œ', 'Œ' -> out.append("oe");
				case 'ł', 'Ł' -> out.append('l');
				case 'đ', 'Đ', 'ð', 'Ð' -> out.append('d');
				case 'þ', 'Þ' -> out.append("th");
				case 'ı' -> out.append('i');
				case 'ħ', 'Ħ' -> out.append('h');
				default -> out.appendCodePoint(Character.toLowerCase(cp));
			}
		}
		return out.toString();
	}

	/// Whether a code point joins the tokens on either side into a compound.
	static boolean isJoiner(int cp) {
		return switch (cp) {
			case '\'', '’', '‘', 'ʼ', 'ʻ', '`', '´', '-', '‐', '‑', '.', '/', '·', '•' -> true;
			default -> false;
		};
	}

	/// Splits already folded text into tokens.
	///
	/// @param folded text from [#fold(String)].
	/// @param withCompounds also emit the concatenation of joined parts, as indexing does.
	/// @param out receives the tokens in order. A compound comes right after its last part.
	static void tokens(String folded, boolean withCompounds, Consumer<String> out) {
		var word = new StringBuilder();
		var compound = new StringBuilder();
		int parts = 0;
		for (int i = 0; i <= folded.length();) {
			int cp = i < folded.length() ? folded.codePointAt(i) : ' ';
			i += i < folded.length() ? Character.charCount(cp) : 1;
			if (Character.isLetterOrDigit(cp)) {
				word.appendCodePoint(cp);
				continue;
			}
			if (!word.isEmpty()) {
				out.accept(word.toString());
				compound.append(word);
				parts++;
				word.setLength(0);
			}
			if (isJoiner(cp) && i < folded.length() && Character.isLetterOrDigit(folded.codePointAt(i)) && parts > 0) {
				continue;
			}
			if (withCompounds && parts > 1) {
				out.accept(compound.toString());
			}
			compound.setLength(0);
			parts = 0;
		}
	}

	/// Folds and tokenizes, returning a list.
	static List<String> tokens(String text, boolean withCompounds) {
		var out = new ArrayList<String>();
		tokens(fold(text), withCompounds, out::add);
		return out;
	}

}
