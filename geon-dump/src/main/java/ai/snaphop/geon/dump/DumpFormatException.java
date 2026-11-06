package ai.snaphop.geon.dump;

import java.io.IOException;

/// The input does not follow the dump format.
///
/// The message says what was expected and where in the input the problem is.
public final class DumpFormatException extends IOException {

	private static final long serialVersionUID = 1L;

	DumpFormatException(String message) {
		super(message);
	}

	DumpFormatException(String message, Throwable cause) {
		super(message, cause);
	}

}
