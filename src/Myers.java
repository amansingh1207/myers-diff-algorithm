import java.util.ArrayList;
import java.util.List;

/**
 * Myers' O(ND) shortest edit script.
 *
 * <p>The sequences are given as {@code int[]} so that one implementation serves both
 * levels: Part A passes one integer per line (interned, so equal lines get equal ids),
 * Part B passes one integer per Unicode code point. Comparison is then just
 * {@code a[x] == b[y]}, which keeps every step of the search O(1).
 *
 * <h2>The algorithm</h2>
 *
 * <p>An edit graph has A along x and B along y. There are three kinds of move:
 * a diagonal (match, cost 0), horizontal (delete A[x], cost 1) and vertical
 * (insert B[y], cost 1). A minimal edit script is the cheapest path from (0,0)
 * to (n,m), and its cost is the edit distance {@code D}.
 *
 * <p>The search sweeps {@code d = 0, 1, 2, ...} and never revisits a smaller {@code d},
 * so the first {@code d} that reaches (n,m) is minimal. For each {@code d} it tracks,
 * on every diagonal {@code k = x - y}, the furthest {@code x} reachable using exactly
 * {@code d} edits — the array {@code V}. Between rounds only {@code d + 1} diagonals are
 * live (those with {@code k} of the same parity as {@code d}), so a snapshot of a round
 * is {@code d + 1} integers rather than the whole array, and the trace costs
 * {@code D(D+1)/2} integers in total.
 *
 * <p>The full script is recovered by walking the snapshots backwards from (n,m).
 */
final class Myers {

    /** This A/B element was matched: advance both. */
    static final byte MATCH = 0;
    /** This A element was removed. */
    static final byte DELETE = 1;
    /** This B element was added. */
    static final byte INSERT = 2;

    private Myers() {
    }

    /**
     * Returns a minimal edit script: one byte per move, in forward order.
     *
     * @param a the first sequence
     * @param b the second sequence
     * @return {@link #MATCH} / {@link #DELETE} / {@link #INSERT} bytes
     */
    static byte[] diff(int[] a, int[] b) {
        int n = a.length;
        int m = b.length;
        int max = n + m;

        int[] v = new int[2 * max + 3];
        int off = max + 1;
        v[off + 1] = 0;

        List<int[]> trace = new ArrayList<>();
        int d = 0;
        int found = -1;

        for (; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                // The canonical Myers tie-break: prefer the diagonal coming from the
                // insertion above, and take the deletion on a tie.
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) {
                    x = v[off + k + 1];
                } else {
                    x = v[off + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break;
                }
            }
            if (found >= 0) {
                break;
            }
            int[] snapshot = new int[d + 1];
            for (int j = 0; j <= d; j++) {
                snapshot[j] = v[off - d + 2 * j];
            }
            trace.add(snapshot);
        }

        int distance = found;
        if (distance < 0) {
            throw new IllegalStateException("search did not reach (" + n + "," + m + ")");
        }
        byte[] ops = new byte[(n + m + distance) / 2];
        backtrack(a, b, trace, distance, ops);
        return ops;
    }

    /**
     * Walks the snapshots back from (n,m), filling {@code ops} from the end.
     *
     * <p>Round {@code d} arrived from round {@code d - 1} either by moving down on the
     * same x (an insertion) or right on the same y (a deletion), and then slid along a
     * diagonal of matches. Reversing that means undoing the matches first, then the step.
     */
    private static void backtrack(int[] a, int[] b, List<int[]> trace, int d0, byte[] ops) {
        int x = a.length;
        int y = b.length;
        int pos = ops.length;

        for (int d = d0; d >= 1; d--) {
            int[] snap = trace.get(d - 1);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && at(snap, d - 1, k - 1) < at(snap, d - 1, k + 1))) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }

            int prevX = at(snap, d - 1, prevK);
            int prevY = prevX - prevK;
            boolean inserted = prevK == k + 1;
            int stepX = inserted ? prevX : prevX + 1;
            int stepY = inserted ? prevY + 1 : prevY;

            while (x > stepX) {
                ops[--pos] = MATCH;
                x--;
                y--;
            }
            ops[--pos] = inserted ? INSERT : DELETE;
            x = prevX;
            y = prevY;
        }
        while (x > 0) {
            ops[--pos] = MATCH;
            x--;
            y--;
        }
    }

    /** Reads diagonal {@code k} out of round {@code r}'s snapshot. */
    private static int at(int[] snapshot, int r, int k) {
        return snapshot[(k + r) / 2];
    }
}
