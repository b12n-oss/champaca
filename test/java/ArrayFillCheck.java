import java.util.Arrays;

// Arrays.fill for each element type, whole and over a range, as the call site
// does it in line, and every case that must reach the JDK's method: a null
// array, a bad range (Arrays.rangeCheck's messages and frame), a value a
// reference array can't hold.
public class ArrayFillCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    interface Run { void run() throws Throwable; }
    static String err(Run r) {
        try { r.run(); return "no exception"; }
        catch (Throwable t) {
            StackTraceElement top = t.getStackTrace()[0];
            return t.getClass().getName() + ": " + t.getMessage() + " at " + top.getClassName() + "." + top.getMethodName();
        }
    }

    public static void main(String[] args) {
        boolean[] z = new boolean[5]; byte[] b = new byte[5]; char[] c = new char[5]; short[] s = new short[5];
        int[] i = new int[5]; long[] j = new long[5]; float[] f = new float[5]; double[] d = new double[5];
        Arrays.fill(z, true); Arrays.fill(b, (byte) -3); Arrays.fill(c, 'q'); Arrays.fill(s, (short) 300);
        Arrays.fill(i, -7); Arrays.fill(j, Long.MIN_VALUE); Arrays.fill(f, 1.5f); Arrays.fill(d, Double.NaN);
        row("whole", Arrays.toString(z) + Arrays.toString(b) + Arrays.toString(c) + Arrays.toString(s)
            + Arrays.toString(i) + Arrays.toString(j) + Arrays.toString(f) + Arrays.toString(d));
        Arrays.fill(z, 1, 3, false); Arrays.fill(b, 0, 2, (byte) 9); Arrays.fill(c, 4, 5, 'z'); Arrays.fill(s, 2, 2, (short) 1);
        Arrays.fill(i, 0, 5, 42); Arrays.fill(j, 3, 4, 7L); Arrays.fill(f, 1, 4, -0.0f); Arrays.fill(d, 0, 1, 2.25);
        row("ranges", Arrays.toString(z) + Arrays.toString(b) + Arrays.toString(c) + Arrays.toString(s)
            + Arrays.toString(i) + Arrays.toString(j) + Arrays.toString(f) + Arrays.toString(d));

        Object[] o = new Object[4]; String[] str = new String[4]; Number[] num = new Number[4];
        Arrays.fill(o, 1); Arrays.fill(str, "s"); Arrays.fill(num, 3.5); Arrays.fill(str, 1, 3, null); Arrays.fill(num, 0, 2, 7);
        row("references", Arrays.toString(o) + Arrays.toString(str) + Arrays.toString(num));
        row("empty Object[] with a wrong value", err(() -> Arrays.fill(new String[0], (Object) 1)));
        row("String[] with an Integer", err(() -> Arrays.fill((Object[]) new String[3], 1)));
        row("String[] range with an Integer", err(() -> Arrays.fill((Object[]) new String[3], 0, 2, 1)));
        row("String[] empty range with an Integer", err(() -> Arrays.fill((Object[]) new String[3], 1, 1, 1)));
        row("null int[]", err(() -> Arrays.fill((int[]) null, 1)));
        row("null Object[]", err(() -> Arrays.fill((Object[]) null, "x")));
        row("from > to", err(() -> Arrays.fill(i, 3, 2, 0)));
        row("from < 0", err(() -> Arrays.fill(i, -1, 2, 0)));
        row("to > length", err(() -> Arrays.fill(i, 0, 6, 0)));
        row("Object[] to > length", err(() -> Arrays.fill(o, 2, 9, "x")));
        row("untouched after failures", Arrays.toString(i) + Arrays.toString(o));
    }
}
