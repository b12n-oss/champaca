import java.lang.reflect.*;
import java.util.*;

// What Method.invoke and Constructor.newInstance do with arguments that
// don't fit: a box for a primitive parameter is unboxed and widened, never
// narrowed; anything else, null for a primitive included, is "argument type
// mismatch". A receiver of another class and the wrong number of arguments
// are refused too, before the method runs.
public class ReflectArgCheck {
    public static String i(int x) { return "int " + x; }
    public static String j(long x) { return "long " + x; }
    public static String f(float x) { return "float " + x; }
    public static String d(double x) { return "double " + x; }
    public static String s(short x) { return "short " + x; }
    public static String b(byte x) { return "byte " + x; }
    public static String c(char x) { return "char " + x; }
    public static String z(boolean x) { return "boolean " + x; }
    public static String str(String x) { return "String " + x; }
    public static String num(Number x) { return "Number " + x; }
    public static String obj(Object x) { return "Object " + x.getClass().getName(); }
    public static String ints(int[] x) { return "int[] " + Arrays.toString(x); }
    public static String strs(String[] x) { return "String[] " + Arrays.toString(x); }
    public static String two(int a, String b) { return "two " + a + " " + b; }
    public static String none() { return "none"; }
    public static String third(double x) { return "third " + (x / 3); }
    public static String fthird(float x) { return "fthird " + (x / 3); }
    public String inst(int x) { return "inst " + x; }
    private static String secret() { return "secret"; }

    public static class Box {
        public final String v;
        public Box(int x) { v = "Box(int " + x + ")"; }
        public Box(String s, long n) { v = "Box(String " + s + ", long " + n + ")"; }
        public String toString() { return v; }
    }

    public static class Lazy {
        static { System.out.println("Lazy initialized"); }
        public Lazy(int x) {}
        public String toString() { return "Lazy"; }
    }

    static String cause(Throwable t) {
        return t.getCause() == null ? "" : " | cause " + t.getCause();
    }
    static void row(String name, Method m, Object recv, Object... args) {
        String r;
        try { r = String.valueOf(m.invoke(recv, args)); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage() + cause(t); }
        System.out.println(name + ": " + r);
    }
    static void ctor(String name, Constructor<?> k, Object... args) {
        String r;
        try { r = String.valueOf(k.newInstance(args)); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage() + cause(t); }
        System.out.println(name + ": " + r);
    }
    static Method m(String n, Class<?>... ps) throws Exception { return ReflectArgCheck.class.getMethod(n, ps); }

    public static void main(String[] args) throws Exception {
        Method i = m("i", int.class), j = m("j", long.class), f = m("f", float.class), d = m("d", double.class);
        Method s = m("s", short.class), b = m("b", byte.class), c = m("c", char.class), z = m("z", boolean.class);
        // the same type, and each widening
        row("int Integer", i, null, 5);
        row("int Short", i, null, (short) 6);
        row("int Byte", i, null, (byte) 7);
        row("int Character", i, null, 'a');
        row("long Integer", j, null, 8);
        row("long Character", j, null, 'b');
        row("float Long", f, null, 9L);
        row("float Integer", f, null, 16777217);
        row("double Float", d, null, 1.5f);
        row("double Long", d, null, 10L);
        row("short Byte", s, null, (byte) 11);
        row("char Character", c, null, 'c');
        row("boolean Boolean", z, null, true);
        // narrowing, other kinds, null
        row("int Long", i, null, 5L);
        row("int Double", i, null, 1.0);
        row("int String", i, null, "5");
        row("int null", i, null, (Object) null);
        row("long null", j, null, (Object) null);
        row("float null", f, null, (Object) null);
        row("double null", d, null, (Object) null);
        row("short null", s, null, (Object) null);
        row("byte null", b, null, (Object) null);
        row("char null", c, null, (Object) null);
        row("boolean null", z, null, (Object) null);
        row("short Character", s, null, 'd');
        row("char Byte", c, null, (byte) 1);
        row("char Short", c, null, (short) 1);
        row("byte Short", b, null, (short) 1);
        row("boolean Integer", z, null, 1);
        row("long Float", j, null, 1.0f);
        // references
        row("String Integer", m("str", String.class), null, 5);
        row("String null", m("str", String.class), null, (Object) null);
        row("Number Integer", m("num", Number.class), null, 5);
        row("Number String", m("num", Number.class), null, "5");
        row("Object int[]", m("obj", Object.class), null, (Object) new int[] {1});
        row("int[] int[]", m("ints", int[].class), null, (Object) new int[] {1, 2});
        row("int[] long[]", m("ints", int[].class), null, (Object) new long[] {1});
        row("int[] Integer[]", m("ints", int[].class), null, (Object) new Integer[] {1});
        row("String[] Object[]", m("strs", String[].class), null, (Object) new Object[] {"a"});
        row("String[] String[]", m("strs", String[].class), null, (Object) new String[] {"a"});
        // the second argument wrong
        row("two ok", m("two", int.class, String.class), null, 1, "x");
        row("two second", m("two", int.class, String.class), null, 1, 2);
        // counts
        row("count less", m("two", int.class, String.class), null, 1);
        row("count more", i, null, 1, 2);
        row("count null array", i, null, (Object[]) null);
        row("none null array", m("none"), null, (Object[]) null);
        row("none empty", m("none"), null);
        // receivers
        Method inst = m("inst", int.class);
        row("receiver ok", inst, new ReflectArgCheck(), 3);
        row("receiver other", inst, "a string", 3);
        row("receiver null", inst, null, 3);
        row("receiver ok, argument wrong", inst, new ReflectArgCheck(), "3");
        // made accessible, Method.invoke skips its own access check, so the
        // receiver is checked by the accessor alone
        Method open = m("inst", int.class);
        open.setAccessible(true);
        row("accessible receiver null", open, null, 3);
        row("accessible receiver other", open, "a string", 3);
        // a widened argument is a real double or float
        row("double Integer arithmetic", m("third", double.class), null, 7);
        row("float Long arithmetic", m("fthird", float.class), null, 7L);
        // constructors
        Constructor<Box> k1 = Box.class.getConstructor(int.class);
        Constructor<Box> k2 = Box.class.getConstructor(String.class, long.class);
        ctor("ctor int Integer", k1, 4);
        ctor("ctor int Character", k1, 'e');
        ctor("ctor int Long", k1, 4L);
        ctor("ctor int null", k1, (Object) null);
        ctor("ctor String,long ok", k2, "s", 5);
        ctor("ctor String,long first wrong", k2, 5, 5L);
        ctor("ctor String,long second wrong", k2, "s", "5");
        ctor("ctor count", k2, "s");
        ctor("ctor JDK StringBuilder Integer", StringBuilder.class.getConstructor(String.class), 5);
        ctor("ctor JDK ArrayList String", java.util.ArrayList.class.getConstructor(java.util.Collection.class), "s");
        ctor("ctor JDK String char[] for byte[]", String.class.getConstructor(byte[].class), (Object) new char[0]);
        ctor("ctor JDK StringBuilder String", StringBuilder.class.getConstructor(String.class), "sb");
        // the class is initialized even when an argument doesn't fit
        Constructor<Lazy> lazy = Lazy.class.getConstructor(int.class);
        ctor("ctor Lazy String", lazy, "x");
        ctor("ctor Lazy int", lazy, 1);
        // a JDK method
        row("JDK concat Integer", String.class.getMethod("concat", String.class), "a", 1);
        row("JDK concat String", String.class.getMethod("concat", String.class), "a", "b");
        row("JDK Math.abs(int) Long", Math.class.getMethod("abs", int.class), null, 1L);
        // caller-sensitive methods called through reflection: with an
        // adapter taking the caller (Class.forName), and without one
        row("CSM Class.forName", Class.class.getMethod("forName", String.class), null, "java.lang.String");
        row("CSM Class.forName wrong", Class.class.getMethod("forName", String.class), null, 5);
        row("CSM Method.invoke", Method.class.getMethod("invoke", Object.class, Object[].class),
            String.class.getMethod("valueOf", int.class), null, new Object[] {7});
        row("CSM Class.getMethod", Class.class.getMethod("getMethod", String.class, Class[].class),
            String.class, "length", new Class<?>[0]);
        Method sa = java.lang.reflect.AccessibleObject.class.getMethod("setAccessible", boolean.class);
        Method priv = ReflectArgCheck.class.getDeclaredMethod("secret");
        row("CSM setAccessible", sa, priv, true);
        row("CSM after setAccessible", priv, null);
    }
}
