import java.lang.invoke.*;
import java.util.*;
import java.util.function.*;

// The JVM's helpful NullPointerException messages (JEP 358): which action
// failed and what was null, worked out from the failing bytecode. bb test
// compiles without -g, so this file's locals print as <localN> and
// <parameterN>; the JDK's classes carry local variable names.
public class NpeCheck {
    static String sf;
    static int[] sints;
    String name;
    NpeCheck next;
    int count;
    Object[] items;

    static String nothing() { return null; }
    NpeCheck self() { return this; }
    static NpeCheck make() { return new NpeCheck(); }
    private int priv() { return count; }

    static void run(String label, Runnable r) {
        try {
            r.run();
            System.out.println(label + ": no exception");
        } catch (NullPointerException e) {
            System.out.println(label + ": " + e.getMessage());
        }
    }

    static int param(String p) { return p.length(); }
    static int paramWide(long a, double b, String c) { return c.length(); }
    int instanceParam(Object o) { return o.hashCode(); }
    static int written(String p) { p = nothing(); return p.length(); }
    static int both(boolean c, String a, String b) { return (c ? a : b).length(); }
    static int merged(boolean c) { String s = c ? null : sf; return s.length(); }
    static int getter(NpeCheck n) { return n.count; }
    static int deep(NpeCheck n) { return n.next.next.name.length(); }
    static String concat(StringBuilder sb, Object[] xs) { return sb.append("x").toString(); }
    int[] ints;
    Object obj;
    int mixed(boolean c) { return (c ? ints : obj).hashCode(); }
    // p is written later in the loop, but HotSpot's analysis reaches the call
    // before the write and never revisits it, so p is still <parameter1>
    static int loopParam(String p, int n) {
        int t = 0;
        for (int i = 0; i < n; i++) { t += p.length(); p = null; }
        return t;
    }

    public static void main(String[] args) throws Exception {
        run("local invoke", () -> { String s = nothing(); s.length(); });
        run("parameter", () -> param(null));
        run("wide parameter", () -> paramWide(1, 2, null));
        run("instance parameter", () -> new NpeCheck().instanceParam(null));
        run("written parameter", () -> written("x"));
        run("this field", () -> new NpeCheck().name.length());
        run("static field", () -> sf.length());
        run("static array field", () -> System.out.println(sints[0]));
        run("return value", () -> nothing().length());
        run("chained return", () -> make().self().next.self());
        run("deep fields", () -> { NpeCheck n = new NpeCheck(); n.next = new NpeCheck(); deep(n); });
        run("read field", () -> getter(null));
        run("assign field", () -> { NpeCheck n = null; n.count = 3; });
        run("assign long field", () -> { NpeHolder h = null; h.big = 3L; });
        run("int array load", () -> { int[] a = null; System.out.println(a[1]); });
        run("long array load", () -> { long[] a = null; System.out.println(a[1]); });
        run("byte array load", () -> { byte[] a = null; System.out.println(a[1]); });
        run("char array store", () -> { char[] a = null; a[0] = 'c'; });
        run("double array store", () -> { double[] a = null; a[0] = 1.0; });
        run("object array store", () -> { Object[] a = null; a[0] = "x"; });
        run("array length", () -> { int[] a = null; System.out.println(a.length); });
        run("array element", () -> { String[] a = new String[400]; a[2].length(); });
        run("array element sipush", () -> { String[] a = new String[400]; a[300].length(); });
        run("array element local index", () -> { String[] a = new String[4]; int i = 3; a[i].length(); });
        run("array of arrays", () -> { int[][] a = new int[2][]; System.out.println(a[1][0]); });
        run("field array element", () -> { NpeCheck n = new NpeCheck(); n.items = new Object[2]; n.items[1].hashCode(); });
        run("throw null", () -> { RuntimeException e = null; throw e; });
        run("synchronized null", () -> { Object o = null; synchronized (o) { System.out.println("in"); } });
        run("unboxing", () -> { Integer i = null; int x = i; System.out.println(x); });
        run("interface call", () -> { List<String> l = null; l.size(); });
        run("parameter types", () -> concat(null, null));
        run("ternary", () -> both(true, null, "y"));
        run("merged local", () -> merged(true));
        run("string switch", () -> {
            String s = nothing();
            switch (s) { case "a": System.out.println("a"); break; default: System.out.println("d"); }
        });
        run("enum switch", () -> {
            Thread.State st = null;
            switch (st) { case NEW: System.out.println("new"); break; default: System.out.println("d"); }
        });
        run("for each", () -> { List<String> l = null; for (String s : l) System.out.println(s); });
        run("mixed ternary", () -> new NpeCheck().mixed(true));
        run("loop parameter", () -> loopParam("ab", 2));
        run("in catch", () -> {
            try { throw new IllegalStateException(); }
            catch (IllegalStateException e) { String s = nothing(); s.length(); }
        });
        run("caught exception", () -> {
            try { throw new IllegalStateException(); }
            catch (IllegalStateException e) { e.getCause().getMessage(); }
        });
        run("assignment chain", () -> { NpeCheck n = null; String r = (n.name = nothing()); System.out.println(r); });
        run("long assignment chain", () -> { NpeHolder h = null; long r = (h.big = 5L); System.out.println(r); });
        run("array assignment chain", () -> { Object[] a = null; Object r = (a[0] = "x"); System.out.println(r); });
        run("long array assignment chain", () -> { long[] a = null; long r = (a[0] = 5L); System.out.println(r); });
        run("private method", () -> { NpeCheck n = null; n.priv(); });
        run("array clone", () -> { int[] a = null; a.clone(); });
        run("method handle", () -> {
            MethodHandle mh = null;
            try { mh.invokeExact(); } catch (RuntimeException e) { throw e; } catch (Throwable t) { throw new IllegalStateException(t); }
        });
        run("var handle", () -> { VarHandle vh = null; vh.get(); });
        run("explicit", () -> { throw new NullPointerException(); });
        run("explicit message", () -> { throw new NullPointerException("given"); });
        run("requireNonNull", () -> Objects.requireNonNull(null));
        run("requireNonNull message", () -> Objects.requireNonNull(null, "must not be null"));
        // inside the JDK, whose classes have local variable names
        run("jdk ArrayList", () -> new ArrayList<String>((Collection<String>) null));
        run("jdk String.join", () -> String.join(",", (Iterable<String>) null));
        run("jdk Optional", () -> Optional.of(null));
        run("jdk TreeMap", () -> new TreeMap<String, String>().put(null, "v"));
        run("jdk lambda", () -> { Function<String, Integer> f = String::length; f.apply(null); });
        run("jdk stream", () -> List.of("a", "b").stream().map(s -> (String) null).map(String::trim).toList());

        // the message survives toString, a second getMessage and a rethrow
        try {
            String s = nothing();
            s.isEmpty();
        } catch (NullPointerException e) {
            System.out.println(e);
            System.out.println(e.getMessage() == e.getMessage() ? "same" : "recomputed");
            try {
                throw e;
            } catch (NullPointerException again) {
                System.out.println(again.getMessage());
            }
        }
        NullPointerException early;
        try {
            Object o = nothing();
            o.toString();
            early = null;
        } catch (NullPointerException e) {
            early = e;
        }
        early.fillInStackTrace();
        System.out.println("after fillInStackTrace: " + early.getMessage());
        // a class defined at run time: the proxy's own unboxing of a null result
        IntSupplier proxied = (IntSupplier) java.lang.reflect.Proxy.newProxyInstance(
            NpeCheck.class.getClassLoader(), new Class<?>[] {IntSupplier.class}, (proxy, m, a) -> null);
        try {
            proxied.getAsInt();
        } catch (NullPointerException e) {
            System.out.println(e.getMessage());
        }
    }
}

class NpeHolder { long big; }
