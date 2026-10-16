import java.util.ArrayList;
import java.util.List;

// Calls the translator inlines or makes direct: small leaf methods in the
// same class, final and private methods, constructors that only call
// Object's. Each must keep the JVM's order of class initialization, null
// checks and exceptions.
public class CallCheck {
    static final List<String> log = new ArrayList<>();

    static class Config {
        static int scale;
        static { log.add("Config.<clinit>"); scale = 3; }
    }

    // leaves: no calls, no loops
    static int twice(int x) { return x * 2; }
    static int scaled(int x) { return x * Config.scale; }       // another class's static
    static int check(int x) { if (x < 0) throw new IllegalArgumentException("neg " + x); return x; }
    static int div(int a, int b) { return a / b; }
    static long wide(long a, double d) { return a + (long) d; }

    int value;
    final int fin() { return value + 1; }
    final int five() { return 5; }                              // never reads this
    private int priv() { return value * 10; }
    int virt() { return value; }

    static final class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        int sum() { return x + y; }                             // final class: direct
    }

    static class Base { int who() { return 1; } }
    static class Derived extends Base { int who() { return 2; } }

    static String nulls() {
        StringBuilder sb = new StringBuilder();
        CallCheck c = null;
        try { c.fin(); sb.append("no NPE"); } catch (NullPointerException e) { sb.append("npe-final"); }
        try { c.five(); sb.append(" no NPE"); } catch (NullPointerException e) { sb.append(" npe-final-no-this"); }
        Point p = null;
        try { p.sum(); sb.append(" no NPE"); } catch (NullPointerException e) { sb.append(" npe-final-class"); }
        String s = null;
        try { s.length(); sb.append(" no NPE"); } catch (NullPointerException e) { sb.append(" npe-string"); }
        return sb.toString();
    }

    // leaves with branches, a switch, longs, type tests and a throw
    static int sw(int k) { switch (k) { case 0: return 10; case 1: return 11; default: return -1; } }
    static long tern(long a, long b) { return a > b ? a - b : b - a; }
    static int isStr(Object o) { return o instanceof String ? 1 : 0; }
    static String asStr(Object o) { return (String) o; }
    static void raise(RuntimeException e) { throw e; }
    final int fm(int x) { return x; }

    static class Top { static int topInits; static { topInits++; } static int ts() { return 7; } }
    static class Below extends Top { static { log.add("Below.<clinit>"); } }

    static String edges() {
        StringBuilder sb = new StringBuilder();
        sb.append(sw(0)).append(' ').append(sw(1)).append(' ').append(sw(5)).append(' ')
          .append(tern(3L, 10L)).append(' ').append(tern(Long.MAX_VALUE, 0L)).append(' ')
          .append(isStr("x")).append(isStr(1)).append(' ').append(asStr("ok"));
        try { asStr(1); } catch (ClassCastException e) { sb.append(" cce"); }
        try { raise(new IllegalStateException("thrown")); } catch (IllegalStateException e) { sb.append(' ').append(e.getMessage()); }
        // arrays at their edges, and null
        int[] a = new int[3];
        int[] none = null;
        for (int i : new int[] { -1, 3, Integer.MIN_VALUE }) {
            try { sb.append(a[i]); } catch (ArrayIndexOutOfBoundsException e) { sb.append(" aioobe"); }
            try { a[i] = 1; } catch (ArrayIndexOutOfBoundsException e) { sb.append(" aioobe-set"); }
        }
        try { sb.append(none[0]); } catch (NullPointerException e) { sb.append(" npe-get"); }
        try { none[0] = 1; } catch (NullPointerException e) { sb.append(" npe-set"); }
        try { sb.append(none.length); } catch (NullPointerException e) { sb.append(" npe-length"); }
        // arguments are evaluated before the call's null check
        CallCheck n = null;
        int zero = 0;
        try { n.fm(1 / zero); } catch (ArithmeticException e) { sb.append(" args-first"); } catch (NullPointerException e) { sb.append(" null-first"); }
        // a superclass's static method through the subclass initializes only the superclass
        int t = Below.ts();
        sb.append(' ').append(t).append(" top ").append(Top.topInits).append(' ').append(log.contains("Below.<clinit>"));
        return sb.toString();
    }

    public static void main(String[] args) {
        log.add("main");
        int r = scaled(5);          // Config initializes here, not before
        log.add("scaled " + r);
        System.out.println(log);

        System.out.println(twice(21) + " " + wide(40L, 2.9) + " " + check(7));
        try { check(-1); } catch (IllegalArgumentException e) { System.out.println("caught " + e.getMessage()); }
        try { div(1, 0); } catch (ArithmeticException e) { System.out.println("div " + e.getMessage()); }

        CallCheck c = new CallCheck();
        c.value = 4;
        System.out.println(c.fin() + " " + c.priv() + " " + c.virt());

        Point p = new Point(3, 4);
        System.out.println(p.sum() + " " + p.x + " " + p.y);

        Base[] bs = { new Base(), new Derived(), new Base() };
        int w = 0;
        for (Base b : bs) w = w * 10 + b.who();
        System.out.println(w);

        System.out.println(nulls());
        System.out.println(edges());

        // String methods the JDK's leaves serve: length, charAt, isEmpty, equals
        String s = "hello";
        System.out.println(s.length() + " " + s.charAt(1) + " " + s.isEmpty() + " " + "".isEmpty()
            + " " + s.equals("hel" + "lo") + " " + s.equals("help"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append((char) ('a' + i % 26));
        sb.append('é').append('中');
        System.out.println(sb.length() + " " + sb.charAt(299) + " " + sb.charAt(300) + " " + sb.charAt(301));
    }
}
