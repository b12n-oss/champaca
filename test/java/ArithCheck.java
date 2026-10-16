// int and long arithmetic at the edges: overflow, negation, every shift with
// negative values and out-of-range counts, narrowing, and iinc wrapping.
public class ArithCheck {
    static int[] ints = {0, 1, -1, 7, -7, 123456789, -123456789, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x7fff0000, 0x80000001};
    static long[] longs = {0L, 1L, -1L, 1L << 40, -(1L << 40), 1L << 60, (1L << 61) + 3, Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789abcdefL};
    static int[] counts = {0, 1, 3, 31, 32, 33, 63, 64, -1, -33};

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
    }
}
