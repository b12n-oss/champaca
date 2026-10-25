// int and long arithmetic at the edges: overflow, negation, every shift with
// negative values and out-of-range counts, narrowing, and iinc wrapping.
public class ArithCheck {
    static int[] ints = {0, 1, -1, 7, -7, 123456789, -123456789, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x7fff0000, 0x80000001};
    static long[] longs = {0L, 1L, -1L, 1L << 40, -(1L << 40), 1L << 60, (1L << 61) + 3, Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789abcdefL};
    static int[] counts = {0, 1, 3, 31, 32, 33, 63, 64, -1, -33};

    static double drem(double x, double y) { return x % y; }
    static float frem(float x, float y) { return x % y; }
    public static void main(String[] args) {
        long h = 17;
        for (int a : ints) {
            for (int b : ints) {
                h = h * 31 + (a + b); h = h * 31 + (a - b); h = h * 31 + (a * b);
                if (b != 0) { h = h * 31 + (a / b); h = h * 31 + (a % b); }
            }
            h = h * 31 + (-a);
            for (int c : counts) { h = h * 31 + (a << c); h = h * 31 + (a >> c); h = h * 31 + (a >>> c); }
            h = h * 31 + (byte) a; h = h * 31 + (short) a; h = h * 31 + (char) a;
        }
        System.out.println("int hash " + h);
        long g = 23;
        for (long a : longs) {
            for (long b : longs) {
                g = g * 31 + (a + b); g = g * 31 + (a - b); g = g * 31 + (a * b);
                if (b != 0) { g = g * 31 + (a / b); g = g * 31 + (a % b); }
            }
            g = g * 31 + (-a);
            for (int c : counts) { g = g * 31 + (a << c); g = g * 31 + (a >> c); g = g * 31 + (a >>> c); }
            g = g * 31 + (int) a;
        }
        System.out.println("long hash " + g);
        System.out.println("edges " + (Integer.MAX_VALUE + 1) + " " + (Integer.MIN_VALUE - 1) + " " + (-Integer.MIN_VALUE)
            + " " + (Long.MAX_VALUE + 1) + " " + (Long.MIN_VALUE * -1) + " " + (Integer.MAX_VALUE * Integer.MAX_VALUE)
            + " " + (1 << 31) + " " + (-1 >>> 1) + " " + (-1L >>> 1) + " " + (int) (1L << 33) + " " + (int) 0xfffffffffL);
        int i = Integer.MAX_VALUE - 2;
        for (int k = 0; k < 5; k++) i++;
        int j = Integer.MIN_VALUE + 1;
        j -= 5;
        System.out.println("iinc " + i + " " + j);

        // % on doubles and floats is fmod: exact, with the dividend's sign,
        // a zero's sign kept, an infinite divisor giving the dividend back
        double[] ds = {5.0, -4.0, -0.0, 0.0, 5.5, -5.5, 1e17, 1e300, Double.MAX_VALUE, Double.MIN_VALUE,
                       2.5e-310, 3.0, 1e-300, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        StringBuilder dr = new StringBuilder();
        for (double x : ds) for (double y : ds) dr.append(x % y).append(' ');
        // through drem/frem, since javac folds a % of two literals itself
        System.out.println("drem " + dr.toString().hashCode() + " " + drem(5.0, Double.POSITIVE_INFINITY) + " " + drem(-4.0, 2.0)
            + " " + drem(-0.0, 3.0) + " " + drem(1e17, 3.0) + " " + drem(Double.MAX_VALUE, 1e-300) + " " + drem(5.5, -2.0)
            + " " + drem(-5.5, 2.0) + " " + drem(Double.MIN_VALUE, 3.0) + " " + drem(1.0, 0.0) + " " + drem(Double.POSITIVE_INFINITY, 2.0));
        float[] fs = {5f, -4f, -0f, 1e10f, Float.MAX_VALUE, Float.MIN_VALUE, 3f, 0.1f, Float.POSITIVE_INFINITY, Float.NaN};
        StringBuilder fr = new StringBuilder();
        for (float x : fs) for (float y : fs) fr.append(x % y).append(' ');
        System.out.println("frem " + fr.toString().hashCode() + " " + frem(1e10f, 3f) + " " + frem(-4f, 2f) + " " + frem(Float.MAX_VALUE, Float.MIN_VALUE)
            + " " + frem(5f, Float.POSITIVE_INFINITY) + " " + frem(0.7f, 0.1f));
        long seed = 42, rh = 0;
        for (int k = 0; k < 2000; k++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double x = Double.longBitsToDouble(seed >>> 2 | 0x3000000000000000L) * ((seed & 1) == 0 ? 1 : -1);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double y = Double.longBitsToDouble(seed >>> 2 | 0x3000000000000000L);
            rh = rh * 31 + Double.doubleToRawLongBits(x % y);
        }
        System.out.println("drem spread " + rh);
    }
}
