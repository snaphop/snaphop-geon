package ai.snaphop.geon.dump;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/// Splits a byte stream into lines without decoding characters. Not thread safe.
///
/// Each dump line is parsed from its own `byte[]` rather than by streaming the whole
/// input through one parser; see `doc/dependency-issues.md` for the avaje bug this
/// avoids.
final class Lines {

	private final InputStream in;

	private final byte[] buffer = new byte[1 << 16];

	private int position;

	private int limit;

	private byte[] line = new byte[1 << 12];

	private long number;

	Lines(InputStream in) {
		this.in = in;
	}

	/// The 1 based number of the line last returned by [#next()].
	long number() {
		return number;
	}

	/// The next line without its line terminator, or `null` at the end of the input.
	byte @Nullable [] next() throws IOException {
		int length = 0;
		boolean any = false;
		while (true) {
			if (position == limit) {
				limit = in.read(buffer, 0, buffer.length);
				position = 0;
				if (limit <= 0) {
					limit = 0;
					if (!any) {
						return null;
					}
					break;
				}
			}
			any = true;
			int start = position;
			while (position < limit && buffer[position] != '\n') {
				position++;
			}
			int count = position - start;
			if (length + count > line.length) {
				line = Arrays.copyOf(line, Math.max(line.length * 2, length + count));
			}
			System.arraycopy(buffer, start, line, length, count);
			length += count;
			if (position < limit) {
				position++; // the newline
				break;
			}
		}
		number++;
		if (length > 0 && line[length - 1] == '\r') {
			length--;
		}
		return Arrays.copyOf(line, length);
	}

	static boolean isBlank(byte[] line) {
		for (byte b : line) {
			if (b != ' ' && b != '\t' && b != '\r') {
				return false;
			}
		}
		return true;
	}

}
