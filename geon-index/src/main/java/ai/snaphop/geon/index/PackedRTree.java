package ai.snaphop.geon.index;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.function.IntPredicate;

import ai.snaphop.geon.GeoPoint;

/// A static R-tree over points, packed in Hilbert curve order, in the style of
/// [flatbush](https://github.com/mourner/flatbush).
///
/// Leaves are the points themselves. Each internal node covers up to [#NODE_SIZE]
/// children. All boxes are stored as integers in 1e-7 degrees.
///
/// ## Layout
///
/// ```
/// int    item count n
/// int    level count
/// int[]  level end positions (in nodes), one per level, leaves first
/// int[]  boxes, 4 per node: min lon, min lat, max lon, max lat
/// int[]  per node: the doc ID for a leaf, or the first child's node position
/// ```
final class PackedRTree {

	static final int NODE_SIZE = 16;

	private PackedRTree() {
	}

	/// Builds the tree section for the given points.
	/// @param lonE7 longitudes in 1e-7 degrees, indexed by doc ID.
	/// @param latE7 latitudes in 1e-7 degrees, indexed by doc ID.
	static ByteSink build(int[] lonE7, int[] latE7) {
		int n = lonE7.length;
		var sink = new ByteSink(64 + n * 24);
		if (n == 0) {
			return sink.i32(0).i32(0);
		}
		long[] keyed = new long[n];
		for (int i = 0; i < n; i++) {
			int hx = (int) (((long) lonE7[i] + 1_800_000_000L) * 0xFFFF / 3_600_000_000L);
			int hy = (int) (((long) latE7[i] + 900_000_000L) * 0xFFFF / 1_800_000_000L);
			keyed[i] = ((long) hilbert(hx, hy) << 32) | i;
		}
		Arrays.sort(keyed);

		// Count nodes per level.
		int[] levelEnds = new int[32];
		int levels = 0;
		int count = n;
		int total = n;
		levelEnds[levels++] = total;
		while (count > 1) {
			count = (count + NODE_SIZE - 1) / NODE_SIZE;
			total += count;
			levelEnds[levels++] = total;
		}
		int[] boxes = new int[total * 4];
		int[] refs = new int[total];
		for (int i = 0; i < n; i++) {
			int doc = (int) keyed[i];
			boxes[i * 4] = lonE7[doc];
			boxes[i * 4 + 1] = latE7[doc];
			boxes[i * 4 + 2] = lonE7[doc];
			boxes[i * 4 + 3] = latE7[doc];
			refs[i] = doc;
		}
		int pos = 0;
		int out = n;
		for (int level = 0; level < levels - 1; level++) {
			int end = levelEnds[level];
			while (pos < end) {
				int first = pos;
				int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
				for (int j = 0; j < NODE_SIZE && pos < end; j++, pos++) {
					minX = Math.min(minX, boxes[pos * 4]);
					minY = Math.min(minY, boxes[pos * 4 + 1]);
					maxX = Math.max(maxX, boxes[pos * 4 + 2]);
					maxY = Math.max(maxY, boxes[pos * 4 + 3]);
				}
				boxes[out * 4] = minX;
				boxes[out * 4 + 1] = minY;
				boxes[out * 4 + 2] = maxX;
				boxes[out * 4 + 3] = maxY;
				refs[out] = first;
				out++;
			}
		}
		sink.i32(n).i32(levels);
		for (int i = 0; i < levels; i++) {
			sink.i32(levelEnds[i]);
		}
		for (int b : boxes) {
			sink.i32(b);
		}
		for (int r : refs) {
			sink.i32(r);
		}
		return sink;
	}

	/// Position on a Hilbert curve of order 16, after the public domain
	/// algorithm by Rawrunprotected.
	static int hilbert(int x, int y) {
		int a = x ^ y;
		int b = 0xFFFF ^ a;
		int c = 0xFFFF ^ (x | y);
		int d = x & (y ^ 0xFFFF);
		int A = a | (b >>> 1);
		int B = (a >>> 1) ^ a;
		int C = ((c >>> 1) ^ (b & (d >>> 1))) ^ c;
		int D = ((a & (c >>> 1)) ^ (d >>> 1)) ^ d;
		a = A; b = B; c = C; d = D;
		A = ((a & (a >>> 2)) ^ (b & (b >>> 2)));
		B = ((a & (b >>> 2)) ^ (b & ((a ^ b) >>> 2)));
		C ^= ((a & (c >>> 2)) ^ (b & (d >>> 2)));
		D ^= ((b & (c >>> 2)) ^ ((a ^ b) & (d >>> 2)));
		a = A; b = B; c = C; d = D;
		A = ((a & (a >>> 4)) ^ (b & (b >>> 4)));
		B = ((a & (b >>> 4)) ^ (b & ((a ^ b) >>> 4)));
		C ^= ((a & (c >>> 4)) ^ (b & (d >>> 4)));
		D ^= ((b & (c >>> 4)) ^ ((a ^ b) & (d >>> 4)));
		a = A; b = B; c = C; d = D;
		C ^= ((a & (c >>> 8)) ^ (b & (d >>> 8)));
		D ^= ((b & (c >>> 8)) ^ ((a ^ b) & (d >>> 8)));
		a = C ^ (C >>> 1);
		b = D ^ (D >>> 1);
		int i0 = x ^ y;
		int i1 = b | (0xFFFF ^ (i0 | a));
		i0 = (i0 | (i0 << 8)) & 0x00FF00FF;
		i0 = (i0 | (i0 << 4)) & 0x0F0F0F0F;
		i0 = (i0 | (i0 << 2)) & 0x33333333;
		i0 = (i0 | (i0 << 1)) & 0x55555555;
		i1 = (i1 | (i1 << 8)) & 0x00FF00FF;
		i1 = (i1 | (i1 << 4)) & 0x0F0F0F0F;
		i1 = (i1 | (i1 << 2)) & 0x33333333;
		i1 = (i1 | (i1 << 1)) & 0x55555555;
		return (i1 << 1) | i0;
	}

	/// Reads a tree section. Thread safe.
	static final class Reader {

		private final MemorySegment s;

		private final int n;

		private final int[] levelEnds;

		private final long boxesOffset;

		private final long refsOffset;

		Reader(MemorySegment s) {
			this.s = s;
			this.n = Seg.i32(s, 0);
			int levels = Seg.i32(s, 4);
			this.levelEnds = new int[levels];
			for (int i = 0; i < levels; i++) {
				levelEnds[i] = Seg.i32(s, 8 + 4L * i);
			}
			int total = levels == 0 ? 0 : levelEnds[levels - 1];
			this.boxesOffset = 8 + 4L * levels;
			this.refsOffset = boxesOffset + 16L * total;
		}

		private int box(int node, int k) {
			return Seg.i32(s, boxesOffset + 16L * node + 4L * k);
		}

		private int ref(int node) {
			return Seg.i32(s, refsOffset + 4L * node);
		}

		/// Visits every doc whose point lies in the box, until the visitor returns `false`.
		void within(int minLon, int minLat, int maxLon, int maxLat, IntPredicate visitor) {
			if (n == 0) {
				return;
			}
			int root = levelEnds[levelEnds.length - 1] - 1;
			int[] stack = new int[64 * NODE_SIZE];
			int[] levelStack = new int[stack.length];
			int sp = 0;
			stack[sp] = root;
			levelStack[sp++] = levelEnds.length - 1;
			while (sp > 0) {
				int node = stack[--sp];
				int level = levelStack[sp];
				if (level == 0) {
					if (box(node, 0) >= minLon && box(node, 0) <= maxLon && box(node, 1) >= minLat
							&& box(node, 1) <= maxLat && !visitor.test(ref(node))) {
						return;
					}
					continue;
				}
				int first = ref(node);
				int end = Math.min(first + NODE_SIZE, levelEnds[level - 1]);
				for (int c = first; c < end; c++) {
					if (box(c, 2) < minLon || box(c, 0) > maxLon || box(c, 3) < minLat || box(c, 1) > maxLat) {
						continue;
					}
					stack[sp] = c;
					levelStack[sp++] = level - 1;
				}
			}
		}

		/// Visits docs in order of increasing distance from a point, stopping beyond
		/// `maxKm` or when the visitor returns `false`.
		///
		/// @param visitor receives the doc ID and its distance in kilometres.
		void nearest(double lon, double lat, double maxKm, NearVisitor visitor) {
			if (n == 0) {
				return;
			}
			record Entry(int node, int level, double km) {
			}
			var queue = new PriorityQueue<Entry>((a, b) -> Double.compare(a.km, b.km));
			int root = levelEnds[levelEnds.length - 1] - 1;
			queue.add(new Entry(root, levelEnds.length - 1, 0));
			while (!queue.isEmpty()) {
				Entry e = queue.poll();
				if (e.km > maxKm) {
					return;
				}
				if (e.level == 0) {
					// A leaf box is the point itself, so its box distance is exact.
					if (!visitor.visit(ref(e.node), e.km)) {
						return;
					}
					continue;
				}
				int first = ref(e.node);
				int end = Math.min(first + NODE_SIZE, levelEnds[e.level - 1]);
				for (int c = first; c < end; c++) {
					double km = boxDistanceKm(lon, lat, c);
					if (km <= maxKm) {
						queue.add(new Entry(c, e.level - 1, km));
					}
				}
			}
		}

		private double boxDistanceKm(double lon, double lat, int node) {
			double minLon = Layout.fromE7(box(node, 0));
			double minLat = Layout.fromE7(box(node, 1));
			double maxLon = Layout.fromE7(box(node, 2));
			double maxLat = Layout.fromE7(box(node, 3));
			double cLon = Math.clamp(lon, minLon, maxLon);
			double cLat = Math.clamp(lat, minLat, maxLat);
			if (cLon == lon && cLat == lat) {
				return 0;
			}
			return GeoPoint.distanceKm(lon, lat, cLon, cLat);
		}

	}

	/// Receives docs from [Reader#nearest].
	@FunctionalInterface
	interface NearVisitor {

		/// @return `false` to stop.
		boolean visit(int doc, double km);

	}

}
