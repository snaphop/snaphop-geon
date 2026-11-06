package ai.snaphop.geon.test;

import io.avaje.json.JsonReader;
import io.avaje.json.stream.JsonStream;

/// Pretty prints JSON so golden strings in tests are readable and diff well.
///
/// Objects get one member per line with two space indents; arrays of plain values
/// stay on one line, such as `[10.0, 50.0]`. Numbers keep their text as received.
final class Json {

	private static final JsonStream JSON = JsonStream.builder().build();

	private Json() {
	}

	static String pretty(String json) {
		var sb = new StringBuilder();
		try (JsonReader r = JSON.reader(json)) {
			value(r, sb, 0);
		}
		return sb.append('\n').toString();
	}

	private static void value(JsonReader r, StringBuilder sb, int depth) {
		switch (r.currentToken()) {
			case BEGIN_OBJECT -> {
				r.beginObject();
				sb.append('{');
				boolean first = true;
				while (r.hasNextField()) {
					sb.append(first ? "\n" : ",\n");
					first = false;
					indent(sb, depth + 1).append(string(r.nextField())).append(": ");
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
				while (r.hasNextElement()) {
					var item = new StringBuilder();
					nested |= r.currentToken() == JsonReader.Token.BEGIN_OBJECT
							|| r.currentToken() == JsonReader.Token.BEGIN_ARRAY;
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
			case STRING -> sb.append(string(r.readString()));
			case BOOLEAN -> sb.append(r.readBoolean());
			case NULL -> {
				r.isNullValue();
				sb.append("null");
			}
			default -> sb.append(r.readDecimal().toString());
		}
	}

	private static String string(String s) {
		var w = JSON.bufferedWriter();
		w.value(s);
		return w.result();
	}

	private static StringBuilder indent(StringBuilder sb, int depth) {
		return sb.append("  ".repeat(depth));
	}

}
