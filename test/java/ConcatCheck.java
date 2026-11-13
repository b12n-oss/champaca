// String concatenation (makeConcatWithConstants) with numbers in it. An int
// or a long reaches the runtime as the number, and its digits are written
// straight into the result: every digit count, both signs, the limits of
// int and long and the values either side of a fixnum's range, next to
// Latin-1 text, text that isn't (the slower path), chars, booleans, nulls
// and objects.
public class ConcatCheck {
    static String mix(String s, int i, long l, char c, boolean b, Object o) {
        return s + i + "|" + l + "|" + c + "|" + b + "|" + o;
    }
    public static void main(String[] args) {
        int[] ints = {0, 1, -1, 9, 10, -10, 99, 100, 999, 1000, 12345, -99999, 1000000000,
                      Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE + 1};
        for (int i : ints) System.out.println("int [" + i + "] " + i + "x" + i);
        long[] longs = {0L, -1L, 9999999999L, -9999999999L, 1L << 59, (1L << 60) - 1, 1L << 60, -(1L << 60),
                        -(1L << 60) - 1, -(1L << 60) + 1, 1L << 61, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1};
        for (long l : longs) System.out.println("long <" + l + "> " + l);
        short sh = -32768; byte by = -128;
        System.out.println("short " + sh + " byte " + by + " " + (short) 32767 + (byte) 127);
        System.out.println(mix("s", 7, 8L, 'c', true, null));
        System.out.println(mix("é", -7, -8L, 'é', false, "str"));
        System.out.println(mix("Ā", 42, Long.MIN_VALUE, 'Ā', true, java.util.List.of(1, 2)));
        System.out.println(mix("😀", Integer.MIN_VALUE, 1L << 60, '\ud83d', false, new StringBuilder("sb")));
        System.out.println(mix(null, 0, 0L, '0', true, Integer.valueOf(-5)));
        System.out.println("" + 1 + 2 + 3L + 4.0f + 5.0 + 'x' + true + null);
        System.out.println(1 + 2 + "three" + 4 + 5);
        String s = "";
        for (int i = -3; i < 12; i++) s = s + i + ",";
        System.out.println(s + " " + s.length());
        long sum = 0;
        for (int i = 0; i < 20000; i++) sum += ("n" + i + "/" + (long) i * 1000003L).length();
        System.out.println("lengths " + sum);
    }
}
