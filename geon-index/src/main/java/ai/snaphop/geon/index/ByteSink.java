package ai.snaphop.geon.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/// A little endian byte buffer used while writing an index. Not thread safe.
///
/// An in memory sink keeps everything in its buffer. A file sink ([#toFile(Path)])
/// writes its buffer out whenever it fills, so it can grow past what fits in memory;
/// call [#close()] when done.
final class ByteSink implements AutoCloseable {

	private static final int FILE_BUFFER = 1 << 20;

	private byte[] bytes;

	private int size;

	private long flushed;

	private final @Nullable FileChannel channel;

	private final @Nullable Path path;

	ByteSink() {
		this(1024);
	}

	ByteSink(int capacity) {
		this.bytes = new byte[Math.max(16, capacity)];
		this.channel = null;
		this.path = null;
	}

	private ByteSink(Path path) throws IOException {
		this.bytes = new byte[FILE_BUFFER];
		this.path = path;
		this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING);
	}

	/// A sink that streams to a file.
	static ByteSink toFile(Path path) throws IOException {
		return new ByteSink(path);
	}

	/// The total number of bytes written so far.
	long size() {
		return flushed + size;
	}

	/// The file of a file sink.
	Path path() {
		if (path == null) {
			throw new IllegalStateException("Not a file sink.");
		}
		return path;
	}

	private void ensure(int extra) {
		if (size + extra <= bytes.length) {
			return;
		}
		if (channel != null) {
			flushBuffer();
			if (extra <= bytes.length) {
				return;
			}
		}
		long wanted = Math.max((long) bytes.length * 2, (long) size + extra);
		if (wanted > Integer.MAX_VALUE - 16) {
			throw new IllegalStateException(
					"An in memory index section cannot exceed 2 GB; this one should be a file sink.");
		}
		bytes = Arrays.copyOf(bytes, (int) wanted);
	}

	private void flushBuffer() {
		var channel = this.channel;
		if (channel == null || size == 0) {
			return;
		}
		try {
			var buffer = ByteBuffer.wrap(bytes, 0, size);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + path, e);
		}
		flushed += size;
		size = 0;
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
		if (len > bytes.length && channel != null) {
			flushBuffer();
			try {
				var buffer = ByteBuffer.wrap(b, off, len);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
			}
			catch (IOException e) {
				throw new UncheckedIOException("Cannot write " + path, e);
			}
			flushed += len;
			return this;
		}
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

	/// Empties an in memory sink so it can be reused.
	void clearMemory() {
		if (channel != null) {
			throw new IllegalStateException("A file sink cannot be cleared.");
		}
		size = 0;
	}

	/// Feeds an in memory sink's content to a digest.
	void digestInto(java.security.MessageDigest digest) {
		if (channel != null) {
			throw new IllegalStateException("A file sink cannot be digested.");
		}
		digest.update(bytes, 0, size);
	}

	/// Appends an in memory sink's content to another sink.
	void copyTo(ByteSink other) {
		if (channel != null) {
			throw new IllegalStateException("Copy a file sink with its path instead.");
		}
		other.bytes(bytes, 0, size);
	}

	/// Writes an in memory sink's content to a channel.
	void writeTo(WritableByteChannel out) throws IOException {
		if (channel != null) {
			throw new IllegalStateException("Copy a file sink with its path instead.");
		}
		var buffer = ByteBuffer.wrap(bytes, 0, size);
		while (buffer.hasRemaining()) {
			out.write(buffer);
		}
	}

	/// Flushes and closes a file sink. Does nothing for an in memory sink.
	@Override
	public void close() {
		var channel = this.channel;
		if (channel == null) {
			return;
		}
		flushBuffer();
		try {
			channel.close();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot close " + path, e);
		}
	}

}
