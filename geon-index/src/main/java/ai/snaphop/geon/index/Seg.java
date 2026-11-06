package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/// Reads little endian values from a memory mapped [MemorySegment].
///
/// Variable length reads return the value and advance a cursor kept in a one element
/// `long[]`, which avoids allocating a cursor object per read on hot paths.
final class Seg {

	static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private Seg() {
	}

	static int u8(MemorySegment s, long offset) {
		return s.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
	}

	static int i32(MemorySegment s, long offset) {
		return s.get(INT, offset);
	}

	static long i64(MemorySegment s, long offset) {
		return s.get(LONG, offset);
	}

	static float f32(MemorySegment s, long offset) {
		return s.get(FLOAT, offset);
	}

	static double f64(MemorySegment s, long offset) {
		return s.get(DOUBLE, offset);
	}

	static int i16(MemorySegment s, long offset) {
		return s.get(SHORT, offset);
	}

	/// Reads an unsigned LEB128 value at `pos[0]` and advances it.
	static long varint(MemorySegment s, long[] pos) {
		long p = pos[0];
		long result = 0;
		int shift = 0;
		while (true) {
			byte b = s.get(ValueLayout.JAVA_BYTE, p++);
			result |= (long) (b & 0x7F) << shift;
			if (b >= 0) {
				pos[0] = p;
				return result;
			}
			shift += 7;
		}
	}

	static int varint32(MemorySegment s, long[] pos) {
		return (int) varint(s, pos);
	}

	/// Reads a zig zag encoded signed value.
	static long zigzag(MemorySegment s, long[] pos) {
		long v = varint(s, pos);
		return (v >>> 1) ^ -(v & 1);
	}

	/// Reads a varint length and that many UTF-8 bytes.
	static String utf8(MemorySegment s, long[] pos) {
		int len = varint32(s, pos);
		String str = utf8(s, pos[0], len);
		pos[0] += len;
		return str;
	}

	static String utf8(MemorySegment s, long offset, int len) {
		byte[] b = new byte[len];
		MemorySegment.copy(s, ValueLayout.JAVA_BYTE, offset, b, 0, len);
		return new String(b, StandardCharsets.UTF_8);
	}

	static byte[] bytes(MemorySegment s, long offset, int len) {
		byte[] b = new byte[len];
		MemorySegment.copy(s, ValueLayout.JAVA_BYTE, offset, b, 0, len);
		return b;
	}

}
