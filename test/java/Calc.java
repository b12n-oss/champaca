public class Calc {
    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    static int sum(int n) { int s = 0; for (int i = 0; i < n; i++) s += i; return s; }
    static int gcd(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t; } return a; }
    static long lmul(long a, long b) { return a * b; }
    public static void main(String[] args) {
        System.out.println(fib(20));
        System.out.println(sum(100000));          // overflows int: must wrap
        System.out.println(gcd(1071, 462));
        System.out.println(Integer.MAX_VALUE + 1);
        System.out.println(-7 / 2);
        System.out.println(-7 % 2);
        System.out.println(lmul(Long.MAX_VALUE, 3));
        System.out.println(1 << 33);
        System.out.println(-1 >>> 28);
    }
}
