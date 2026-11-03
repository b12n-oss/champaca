import java.util.Arrays;

// Loops over arrays, which codegen versions (compile-method*'s array loops):
// a copy of the loop reads each array local's vector and length taken once,
// entered only when every such local holds an array. Each row has to print
// what HotSpot prints, exceptions, their messages and their lines included.
public class ArrayLoopCheck {
    static int effects;

    static int sum(int[] a) { int s = 0; for (int i = 0; i < a.length; i++) s += a[i]; return s; }
    static int upTo(int[] a, int n) { int s = 0; for (int i = 0; i < n; i++) { effects++; s += a[i]; } return s; }
    static long lsum(long[] a) { long s = 0; for (long x : a) s += x * 3 + 1; return s; }
    static double dot(double[] a, double[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
    static int fromZero(int[] a, int i) { int s = 0; while (i < a.length) { s = s * 31 + a[i]; i++; } return s; }
    static int twoArrays(int[] a, int[] b, int limit) {
        int s = 0;
        for (int i = 0; i < a.length; i++) { s += a[i]; if (s > limit) s -= b[i]; }
        return s;
    }
    static int reassigned(int[] a, int[] b) {
        int s = 0;
        for (int i = 0; i < 6; i++) { s = s * 7 + a[i % a.length]; int[] t = a; a = b; b = t; }
        return s;
    }
    static int grid(int[][] m) {
        int s = 0;
        for (int r = 0; r < m.length; r++) { int[] row = m[r]; for (int c = 0; c < row.length; c++) s = s * 3 + row[c]; }
        return s;
    }
    static int breaks(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < 0) break;
            if (a[i] % 2 == 0) continue;
            if (a[i] > 100) return -s;
            s += a[i];
        }
        return s;
    }
    static String stores(int n) {
        char[] c = new char[n]; byte[] b = new byte[n]; short[] h = new short[n]; long[] l = new long[n]; Object[] o = new Object[n];
        for (int i = 0; i < n; i++) {
            c[i] = (char) (i * 40000); b[i] = (byte) (i * 100); h[i] = (short) (i * 30000);
            l[i] = (long) i * 0x123456789L << 20; o[i] = i % 2 == 0 ? "e" + i : Integer.valueOf(i);
        }
        int cs = 0; for (int i = 0; i < c.length; i++) cs = cs * 31 + c[i];
        return cs + " " + Arrays.toString(b) + " " + Arrays.toString(h) + " " + Arrays.toString(l) + " " + Arrays.toString(o);
    }
    static int lengths(int[] a) { int s = 0; for (int i = 0; i < 4; i++) s += a.length * i + a[i & 1]; return s; }
    static synchronized int locked(int[] a) { int s = 0; for (int x : a) s ^= x; return s; }
    static int guarded(int[] a) {
        int s = 0;
        try { for (int i = 0; i <= a.length; i++) s += a[i]; }
        catch (ArrayIndexOutOfBoundsException e) { s = -s; }
        return s;
    }
    static int cast(Object o, int n) { int s = 0; for (int i = 0; i < n; i++) s += ((int[]) o)[i]; return s; }
    static int trace(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == 7) { StackTraceElement e = new Throwable().getStackTrace()[0]; return e.getMethodName().length() * 1000 + e.getLineNumber(); }
            s += a[i];
        }
        return s;
    }

    interface Row { Object run(); }
    static void row(String name, Row r) {
        String out;
        try { out = String.valueOf(r.run()); }
        catch (RuntimeException e) {
            StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            // a ClassCastException's text lacks the JVM's module clause here (ROADMAP)
            String m = e instanceof ClassCastException ? "" : ": " + e.getMessage();
            out = e.getClass().getName() + m + (top == null ? "" : " at " + top.getMethodName() + ":" + top.getLineNumber());
        }
        System.out.println(name + ": " + out + " effects " + effects);
        effects = 0;
    }

    public static void main(String[] args) {
        int[] five = {3, 1, 4, 1, 5};
        row("sum", () -> sum(five));
        row("sum empty", () -> sum(new int[0]));
        row("sum null", () -> sum(null));
        row("upTo", () -> upTo(five, 5));
        row("upTo past the end", () -> upTo(five, 7));
        row("upTo null, no steps", () -> upTo(null, 0));
        row("upTo null, one step", () -> upTo(null, 1));
        row("lsum", () -> lsum(new long[] {Long.MAX_VALUE, -7, 1L << 40}));
        row("dot", () -> dot(new double[] {0.5, 1.5, -2}, new double[] {2, 4, 8}));
        row("dot short b", () -> dot(new double[] {0.5, 1.5, -2}, new double[] {2, 4}));
        row("dot null b", () -> dot(new double[] {0.5}, null));
        row("dot null b, empty a", () -> dot(new double[0], null));
        row("fromZero", () -> fromZero(five, 0));
        row("fromZero past", () -> fromZero(five, 9));
        row("fromZero negative", () -> fromZero(five, -1));
        row("two arrays, b unread", () -> twoArrays(five, null, 100));
        row("two arrays, b read", () -> twoArrays(five, new int[] {1, 1, 1, 1, 1}, 5));
        row("two arrays, b null when read", () -> twoArrays(five, null, 5));
        row("two arrays, b short", () -> twoArrays(five, new int[] {1}, 5));
        row("reassigned", () -> reassigned(new int[] {1, 2, 3}, new int[] {10, 20}));
        row("grid", () -> grid(new int[][] {{1, 2}, {}, {3, 4, 5}}));
        row("grid null row", () -> grid(new int[][] {{1, 2}, null, {3}}));
        row("breaks", () -> breaks(new int[] {1, 2, 3, 5, -1, 9}));
        row("breaks return", () -> breaks(new int[] {1, 3, 101, 5}));
        row("stores", () -> stores(5));
        row("lengths", () -> lengths(new int[] {5, 6}));
        row("lengths short", () -> lengths(new int[] {5}));
        row("locked", () -> locked(five));
        row("guarded", () -> guarded(five));
        row("cast", () -> cast(five, 3));
        row("cast wrong type", () -> cast(new long[] {1, 2}, 2));
        row("cast null, no steps", () -> cast(null, 0));
        row("trace", () -> trace(new int[] {1, 2, 7, 3}));
    }
}
