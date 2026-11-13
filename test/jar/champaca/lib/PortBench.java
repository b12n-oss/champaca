package champaca.lib;

import java.util.ArrayList;
import java.util.List;

/** Times each PortSample method, one line a method: jolt bench:port. */
public class PortBench {
    interface Body { long run(); }
    static long sink;
    static void time(String name, int n, Body b) {
        long best = Long.MAX_VALUE, r = 0;
        for (int pass = 0; pass < 5; pass++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) r += b.run();
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.println(name + " " + (best / n));
        sink += r;
    }
    public static void main(String[] a) {
        int[] xs = new int[1000]; for (int i = 0; i < 1000; i++) xs[i] = i * 7919 - 3000000;
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < 100; i++) sb.append((char) ('a' + i % 7));
        String half = sb.toString(), pal = half + sb.reverse();
        StringBuilder ds = new StringBuilder(); for (int i = 0; i < 200; i++) ds.append((char) ('0' + i % 10));
        String digits = ds.toString();
        List<String> parts = new ArrayList<>(); for (int i = 0; i < 20; i++) parts.add("  part" + i + " ");
        time("sumEven", 20000, () -> PortSample.sumEven(xs));
        time("gcd", 1000000, () -> PortSample.gcd(1836311903, 1134903170));
        time("fib", 1000000, () -> PortSample.fib(40));
        time("clamp", 5000000, () -> PortSample.clamp(5, 1, 3));
        time("reverse", 20000, () -> { PortSample.reverse(xs); return xs[0]; });
        time("countChar", 50000, () -> PortSample.countChar(pal, 'a'));
        time("isPalindrome", 50000, () -> PortSample.isPalindrome(pal) ? 1 : 0);
        time("digitSum", 50000, () -> PortSample.digitSum(digits));
        time("divAll", 20000, () -> PortSample.divAll(xs, 7)[3]);
        time("hash31", 50000, () -> PortSample.hash31(pal));
        time("joinTrim", 50000, () -> PortSample.joinTrim(parts, ", ").length());
    }
}
