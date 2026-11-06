package ai.snaphop.geon.index;

/// Bounded Damerau Levenshtein distance (optimal string alignment), so a swap of two
/// neighbouring letters counts as one edit.
final class EditDistance {

	private EditDistance() {
	}

	/// The distance between `a` and `b`, or `max + 1` as soon as it is known to exceed `max`.
	static int bounded(String a, String b, int max) {
		int[] x = a.codePoints().toArray();
		int[] y = b.codePoints().toArray();
		if (Math.abs(x.length - y.length) > max) {
			return max + 1;
		}
		int[] prev2 = new int[y.length + 1];
		int[] prev = new int[y.length + 1];
		int[] cur = new int[y.length + 1];
		for (int j = 0; j <= y.length; j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= x.length; i++) {
			cur[0] = i;
			int rowMin = cur[0];
			for (int j = 1; j <= y.length; j++) {
				int cost = x[i - 1] == y[j - 1] ? 0 : 1;
				int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
				if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) {
					v = Math.min(v, prev2[j - 2] + 1);
				}
				cur[j] = v;
				rowMin = Math.min(rowMin, v);
			}
			if (rowMin > max) {
				return max + 1;
			}
			int[] t = prev2;
			prev2 = prev;
			prev = cur;
			cur = t;
		}
		return Math.min(prev[y.length], max + 1);
	}

}
