package ai.snaphop.geon.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// A growable little endian byte buffer used while writing an index. Not thread safe.
final class ByteSink {

	private byte[] bytes;

	private int size;

	ByteSink() {
		this(1024);
	}

	ByteSink(int capacity) {
		this.bytes = new byte[Math.max(16, capacity)];
	}

	long size() {
		return size;
	}

	private void ensure(int extra) {
		if (size + extra > bytes.length) {
			long wanted = Math.max((long) bytes.length * 2, (long) size + extra);
			if (wanted > Integer.MAX_VALUE - 16) {
				throw new IllegalStateException("Index section larger than 2 GB is not supported yet.");
			}
			bytes = Arrays.copyOf(bytes, (int) wanted);
		}
	}

	ByteSink u8(int v) {
		ensure(1);
		bytes[size++] = (byte) v;
		return this;
	}

	ByteSink i16(int v) {
		ensure(2);
		bytes[size++] = (byte) v;
		bytes[size++] = (byte) (v >>> 8);
		return this;
	}

	ByteSink i32(int v) {
		ensure(4);
		for (int i = 0; i < 4; i++) {
			bytes[size++] = (byte) (v >>> (8 * i));
		}
		return this;
	}

	ByteSink i64(long v) {
		ensure(8);
		for (int i = 0; i < 8; i++) {
			bytes[size++] = (byte) (v >>> (8 * i));
		}
		return this;
	}

	ByteSink f32(float v) {
		return i32(Float.floatToRawIntBits(v));
	}

	ByteSink f64(double v) {
		return i64(Double.doubleToRawLongBits(v));
	}

	/// Unsigned LEB128.
	ByteSink varint(long v) {
		ensure(10);
		while ((v & ~0x7FL) != 0) {
			bytes[size++] = (byte) ((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		bytes[size++] = (byte) v;
		return this;
	}

	/// Zig zag encoded signed LEB128.
	ByteSink zigzag(long v) {
		return varint((v << 1) ^ (v >> 63));
	}

	ByteSink bytes(byte[] b) {
		return bytes(b, 0, b.length);
	}

	ByteSink bytes(byte[] b, int off, int len) {
		ensure(len);
		System.arraycopy(b, off, bytes, size, len);
		size += len;
		return this;
	}

	/// A varint length followed by UTF-8 bytes.
	ByteSink utf8(String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		varint(b.length);
		return bytes(b);
	}

	/// Writes the content to a channel.
	void writeTo(WritableByteChannel channel) throws IOException {
		var buffer = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN);
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

}
