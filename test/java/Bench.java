public class Bench {
    static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    static long loop(int n) { long s = 0; for (int i = 0; i < n; i++) { s += i ^ (i >>> 3); } return s; }
    public static void main(String[] args) {
        long t0 = 0;
        System.out.println(fib(32));
        System.out.println(loop(50000000));
    }
}
