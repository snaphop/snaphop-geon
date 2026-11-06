package ai.snaphop.geon.index;

import java.util.Arrays;

/// A growable list of primitive ints. Not thread safe.
final class IntList {

	private int[] values = new int[4];

	private int size;

	int size() {
		return size;
	}

	int get(int i) {
		return values[i];
	}

	void add(int v) {
		if (size == values.length) {
			values = Arrays.copyOf(values, size * 2);
		}
		values[size++] = v;
	}

	void addAll(IntList other) {
		for (int i = 0; i < other.size; i++) {
			add(other.values[i]);
		}
	}

	void clear() {
		size = 0;
	}

	/// Sorts ascending, removes duplicates and keeps at most `max` values.
	void sortUniqueTruncate(int max) {
		Arrays.sort(values, 0, size);
		int out = 0;
		for (int i = 0; i < size && out < max; i++) {
			if (out == 0 || values[out - 1] != values[i]) {
				values[out++] = values[i];
			}
		}
		size = out;
	}

	int[] toArray() {
		return Arrays.copyOf(values, size);
	}

}
