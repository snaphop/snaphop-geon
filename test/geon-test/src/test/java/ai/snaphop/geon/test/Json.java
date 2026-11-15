package ai.snaphop.geon.test;

import com.snaphop.jsonl.JsonReader;
import com.snaphop.jsonl.JsonWriter;

/// Pretty prints JSON so golden strings in tests are readable and diff well.
///
/// Objects get one member per line with two space indents; arrays of plain values
/// stay on one line, such as `[10.0, 50.0]`. Numbers keep their text as received.
final class Json {

	private Json() {
	}

	static String pretty(String json) {
		var sb = new StringBuilder();
		try (JsonReader r = JsonReader.of(json)) {
			value(r, sb, 0);
		}
		return sb.append('\n').toString();
	}

	private static void value(JsonReader r, StringBuilder sb, int depth) {
		switch (r.peek()) {
			case BEGIN_OBJECT -> {
				r.beginObject();
				sb.append('{');
				boolean first = true;
				while (r.hasNext()) {
					sb.append(first ? "\n" : ",\n");
					first = false;
					indent(sb, depth + 1).append(string(r.nextName())).append(": ");
					value(r, sb, depth + 1);
				}
				r.endObject();
				if (!first) {
					indent(sb.append('\n'), depth);
				}
				sb.append('}');
			}
			case BEGIN_ARRAY -> {
				r.beginArray();
				var items = new java.util.ArrayList<String>();
				boolean nested = false;
				while (r.hasNext()) {
					var item = new StringBuilder();
					nested |= r.peek() == JsonReader.Token.BEGIN_OBJECT
							|| r.peek() == JsonReader.Token.BEGIN_ARRAY;
					value(r, item, depth + 1);
					items.add(item.toString());
				}
				r.endArray();
				if (!nested) {
					sb.append('[').append(String.join(", ", items)).append(']');
				}
				else {
					sb.append('[');
					for (int i = 0; i < items.size(); i++) {
						indent(sb.append(i == 0 ? "\n" : ",\n"), depth + 1).append(items.get(i));
					}
					indent(sb.append('\n'), depth).append(']');
				}
			}
			case STRING -> sb.append(string(r.nextString()));
			case BOOLEAN -> sb.append(r.nextBoolean());
			case NULL -> {
				r.nextNull();
				sb.append("null");
			}
			default -> sb.append(r.nextBigDecimal().toString());
		}
	}

	private static String string(String s) {
		var w = JsonWriter.ofBytes();
		w.value(s);
		return w.toString();
	}

	private static StringBuilder indent(StringBuilder sb, int depth) {
		return sb.append("  ".repeat(depth));
	}

}
