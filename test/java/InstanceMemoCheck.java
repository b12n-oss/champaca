import java.util.*;

// One instanceof or checkcast site fed many receiver classes, several times
// over: the answer must be the same on the first pass and on every later one.
public class InstanceMemoCheck {
    interface Seqable { int seq(); }
    interface Counted { int count(); }
    interface Marker {}
    interface SubMarker extends Marker {}
    static abstract class Base implements Seqable {}
    static class A extends Base implements Counted, Marker { public int seq() { return 1; } public int count() { return 1; } }
    static class B extends Base implements SubMarker { public int seq() { return 2; } }
    static class C extends Base implements Counted { public int seq() { return 3; } public int count() { return 3; } }
    static class D extends Base { public int seq() { return 4; } }
    static class E extends A { public int seq() { return 5; } }
    static class F extends B implements Counted { public int seq() { return 6; } public int count() { return 6; } }
    static class G implements Seqable, Comparable<G> { public int seq() { return 7; } public int compareTo(G o) { return 0; } }

    static String tests(Object x) {
        StringBuilder sb = new StringBuilder();
        sb.append(x instanceof Counted ? 'C' : '-');
        sb.append(x instanceof Marker ? 'M' : '-');
        sb.append(x instanceof SubMarker ? 'S' : '-');
        sb.append(x instanceof Base ? 'B' : '-');
        sb.append(x instanceof A ? 'A' : '-');
        sb.append(x instanceof Comparable ? 'P' : '-');
        sb.append(x instanceof Object[] ? 'O' : '-');
        sb.append(x instanceof Seqable[] ? 'Q' : '-');
        sb.append(x instanceof Cloneable ? 'L' : '-');
        return sb.toString();
    }

    static String cast(Object x) {
        try {
            Counted c = (Counted) x;
            return c == null ? "null passes" : "count " + c.count();
        } catch (ClassCastException e) {
            return "CCE " + e.getMessage().replaceAll(" \\(.*", "");
        }
    }

    static String name(Object x) {
        return x == null ? "null" : x.getClass().getSimpleName();
    }

    public static void main(String[] args) {
        Object[] xs = { new A(), new B(), new C(), new D(), new E(), new F(), new G(), "text", 42,
                        new Object[0], new Seqable[0], new A[0], new int[0], null, new ArrayList<String>() };
        for (int pass = 0; pass < 3; pass++) {
            StringBuilder line = new StringBuilder("pass " + pass + ":");
            for (Object x : xs) line.append(' ').append(name(x)).append('=').append(tests(x));
            System.out.println(line);
        }
        for (int pass = 0; pass < 2; pass++)
            for (Object x : xs) System.out.println("cast " + pass + " " + name(x) + ": " + cast(x));
        // the same answers in reverse order, so no site's first class is special
        StringBuilder line = new StringBuilder("reversed:");
        for (int i = xs.length - 1; i >= 0; i--) line.append(' ').append(name(xs[i])).append('=').append(tests(xs[i]));
        System.out.println(line);
        long n = 0;
        for (int r = 0; r < 20000; r++)
            for (Object x : xs) { if (x instanceof Counted c) n += c.count(); if (x instanceof Marker) n += 100; }
        System.out.println("sum " + n);
    }
}
