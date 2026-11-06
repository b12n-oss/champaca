import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

// ClassCastException texts: a failed checkcast names both classes in
// HotSpot's form, with their modules and loaders.
public class CastCheck {
    public static class A {}
    public static class B extends A {}
    public interface Shape {}

    // only Loader defines P and Q: the application loader never loads them
    public static class P {}
    public static class Q {}
    public static class H {}
    static byte[] bytes(String name) throws Exception {
        try (var in = CastCheck.class.getResourceAsStream(name + ".class")) { return in.readAllBytes(); }
    }
    static class Loader extends ClassLoader {
        Loader(String name) { super(name, CastCheck.class.getClassLoader()); }
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("CastCheck$P") && !name.equals("CastCheck$Q")) return super.loadClass(name, resolve);
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;
            try { byte[] b = bytes(name); return defineClass(name, b, 0, b.length); }
            catch (Exception e) { throw new ClassNotFoundException(name, e); }
        }
        Object make(String name) throws Exception {
            var c = loadClass(name); var k = c.getDeclaredConstructor(); k.setAccessible(true); return k.newInstance();
        }
    }

    interface Op { Object run() throws Throwable; }
    static void row(String name, Op op) {
        String r;
        try { r = String.valueOf(op.run()); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(name + ": " + r.replaceAll("@[0-9a-f]+", "@H").replaceAll("/0x[0-9a-f]+", "/0xN"));
    }
    static Object id(Object o) { return o; }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        row("Long to Integer", () -> (Integer) id(5L));
        row("String to Number", () -> (Number) id("x"));
        row("user to String", () -> (String) id(new A()));
        row("String to user", () -> (A) id("x"));
        row("user to user", () -> (B) id(new A()));
        row("user to interface", () -> (Shape) id(new A()));
        row("Integer to Runnable", () -> (Runnable) id(1));
        row("platform to String", () -> (String) id(new java.sql.Timestamp(0)));
        row("String to platform", () -> (java.sql.Timestamp) id("x"));
        row("Object[] to String[]", () -> (String[]) id(new Object[0]));
        row("A[] to String[]", () -> (String[]) id(new A[0]));
        row("String[] to A[]", () -> (A[]) id(new String[0]));
        row("int[] to long[]", () -> (long[]) id(new int[0]));
        row("int[] to Object[]", () -> (Object[]) id(new int[0]));
        row("int[][] to long[][]", () -> (long[][]) id(new int[0][0]));
        row("A[][] to A[]", () -> (A[]) id(new A[0][0]));
        row("user to int[]", () -> (int[]) id(new A()));
        row("jdk module", () -> (String) id(jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE));
        row("proxy", () -> (String) id(java.lang.reflect.Proxy.newProxyInstance(
                CastCheck.class.getClassLoader(), new Class<?>[] {Shape.class}, (p, m, a) -> null)));
        row("generic", () -> {
            List<Object> raw = new ArrayList<>(); raw.add(7);
            List<String> l = (List<String>) (List<?>) raw;
            String s = l.get(0); return s; });
        row("inside JDK code", () -> {
            TreeMap<Object, Object> m = new TreeMap<>(); m.put(new A(), 1); m.put(new A(), 2); return m; });
        Loader own = new Loader(null);
        row("own loader", () -> (String) own.make("CastCheck$P"));
        row("own loader to user", () -> (A) own.make("CastCheck$P"));
        row("named loader", () -> (String) new Loader("plug").make("CastCheck$Q"));
        row("hidden", () -> (String) MethodHandles.lookup()
                .defineHiddenClass(bytes("CastCheck$H"), true).lookupClass().getDeclaredConstructor().newInstance());
        row("null passes", () -> (String) id(null));
        row("asType", () -> MethodHandles.identity(Object.class)
                .asType(MethodType.methodType(String.class, Object.class)).invoke((Object) 1));
        row("Class.cast", () -> String.class.cast(1));
    }
}
