package champaca.lib;

import java.util.List;

/** Small static methods for the translation checks (examples/portcheck.clj). */
public class PortSample {
    public static int sumEven(int[] xs) {
        int s = 0;
        for (int x : xs) if (x % 2 == 0) s += x;
        return s;
    }
    public static int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return a;
    }
    public static int fib(int n) {
        int a = 0, b = 1;
        for (int i = 0; i < n; i++) { int t = a + b; a = b; b = t; }
        return a;
    }
    public static long clamp(long v, long lo, long hi) {
        if (lo > hi) throw new IllegalArgumentException("lo > hi");
        return v < lo ? lo : v > hi ? hi : v;
    }
    public static void reverse(int[] xs) {
        for (int i = 0, j = xs.length - 1; i < j; i++, j--) { int t = xs[i]; xs[i] = xs[j]; xs[j] = t; }
    }
    public static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
    public static boolean isPalindrome(String s) {
        for (int i = 0, j = s.length() - 1; i < j; i++, j--) if (s.charAt(i) != s.charAt(j)) return false;
        return true;
    }
    public static int digitSum(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new NumberFormatException("not a digit: " + c);
            n += c - '0';
        }
        return n;
    }
    public static int[] divAll(int[] xs, int d) {
        int[] out = new int[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = xs[i] / d;
        return out;
    }
    public static int hash31(String s) {
        int h = 7;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }
    static final int[] SQUARES = {0, 1, 4, 9};
    /** Reads a static field of the class, which no rule covers: it is left. */
    public static int square(int i) { return SQUARES[i & 3]; }
    /** A swap: both locals change at once. */
    public static int span(int start, int end) {
        if (start > end) { int t = start; start = end; end = t; }
        return end - start;
    }
    /** Calls two other methods of the class. */
    public static int gcdOfSpans(int a, int b, int c) {
        return gcd(span(a, b), span(b, c));
    }
    /** Overloads: the functions are named by their parameter types. */
    public static boolean within(int x, int hi) { return within(x, 0, hi); }
    public static boolean within(int x, int lo, int hi) { return x >= lo && x < hi; }
    /** Reads a char[]: an element is a char on the jolt side and its code here. */
    public static int countIn(char[] a, char c) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] == c) n++;
        return n;
    }
    /** Makes a char[], writes it, and makes a string of it. */
    public static String mirror(String s) {
        char[] a = s.toCharArray();
        char[] b = new char[a.length * 2];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i];
            b[b.length - 1 - i] = (char) (a[i] + 1);
        }
        return new String(b);
    }
    /** new String(char[], int, int), whose range Java checks. */
    public static String slice(char[] a, int from, int count) {
        return new String(a, from, count);
    }
    /** Writes into the caller's char[] and answers it. */
    public static char[] fillFrom(char[] a, int from, char c) {
        for (int i = from; i < a.length; i++) a[i] = c;
        return a;
    }
    /** Makes a String[] and stores into it. */
    public static String[] ends(String s) {
        String[] r = new String[2];
        if (s.length() > 0) {
            r[0] = s.substring(0, 1);
            r[1] = s.substring(s.length() - 1);
        }
        return r;
    }
    /** A boolean[] shares its instructions with a byte[]: left. */
    public static int countTrue(boolean[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i]) n++;
        return n;
    }
    public static String joinTrim(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i).trim());
        }
        return sb.toString();
    }
}
