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
    public static String joinTrim(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i).trim());
        }
        return sb.toString();
    }
}
