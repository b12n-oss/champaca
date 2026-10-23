import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;

// Classes defined at run time: ClassLoader.defineClass from bytes and from a
// ByteBuffer, Lookup.defineClass and defineHiddenClass (with class data),
// and java.lang.reflect.Proxy on top of them. The bytes are this program's
// own nested classes, read back as resources. Tiny2 to Tiny5 are never named
// in code, so the only class of each name is the one defined here.
public class DefineCheck {
    public static class Tiny2 { public static int v() { return 7; } public String toString() { return "tiny2"; } }
    public static class Tiny3 { public static String v() { return "three"; } }
    public static class Tiny4 { public static int v() { return 4; } }
    public static class Tiny5 { public static int v() { return 5; } }
    public static class Tiny6 { public static int v() { return 6; } }
    public static class Tiny7 { public static int v() { return 7; } }
    public static class Tiny8 { public static int v() { return 8; } }
    // a class that names itself in a catch clause and in an array type: a hidden
    // copy must find itself under its new name in both
    public static class TinyEx extends RuntimeException {
        public static String t() { try { throw new TinyEx(); } catch (TinyEx e) { return "caught"; } }
        public static Object arr() { return new TinyEx[2][]; }
    }
    public static class Orphan extends Tiny8 {}

    // defines one class the first time anyone asks for it; its parent (the
    // bootstrap or platform loader) never finds the classpath's copy first
    static class Lazy extends ClassLoader {
        final String simple;
        Lazy(ClassLoader parent, String simple) { super(parent); this.simple = simple; }
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals("DefineCheck$" + simple)) throw new ClassNotFoundException(name);
            try { byte[] b = bytes(simple); return defineClass(name, b, 0, b.length); }
            catch (Exception e) { throw new ClassNotFoundException(name, e); }
        }
    }

    static byte[] bytes(String simple) throws Exception {
        try (var in = DefineCheck.class.getResourceAsStream("DefineCheck$" + simple + ".class")) { return in.readAllBytes(); }
    }

    static String fails(Callable<?> c) {
        try { c.call(); return "no exception"; } catch (Throwable t) { return t.getClass().getName(); }
    }

    static class Loader extends ClassLoader {
        Loader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
        Class<?> define(String name, ByteBuffer b) { return defineClass(name, b, null); }
    }

    public static void main(String[] args) throws Exception {
        ClassLoader scl = ClassLoader.getSystemClassLoader();
        Loader cl = new Loader(scl);
        byte[] t2 = bytes("Tiny2");
        Class<?> c = cl.define("DefineCheck$Tiny2", t2);
        System.out.println("defined: " + c.getName() + " " + c.getMethod("v").invoke(null) + " " + c.getDeclaredConstructor().newInstance()
                           + " " + (c.getClassLoader() == cl) + " " + c.getModule().isNamed() + " " + (c.getModule() == cl.getUnnamedModule())
                           + " " + c.getSuperclass().getName() + " " + c.getPackageName().isEmpty() + " " + c.isHidden());
        // its own loader finds it; the errors a bad definition throws. (The application loader
        // would load the classpath's own copy of the same name on the JVM, where champaca has
        // one class table: not a row.)
        System.out.println("found through its loader: " + (cl.loadClass("DefineCheck$Tiny2") == c) + " " + (Class.forName("DefineCheck$Tiny2", false, cl) == c)
                           + " " + fails(() -> cl.define("DefineCheck$Tiny2", t2)) + " " + fails(() -> cl.define("Wrong", t2))
                           + " " + fails(() -> cl.define(null, new byte[] {1, 2, 3})) + " " + fails(() -> cl.define(null, Arrays.copyOf(t2, t2.length / 2)))
                           + " " + fails(() -> cl.define("java.lang.Nope", t2)));
        // Class.forName asks a loader of the program's own through its loadClass, which here defines the class
        Lazy lazy = new Lazy(null, "Tiny6");
        Class<?> c6 = Class.forName("DefineCheck$Tiny6", true, lazy);
        Lazy lazy7 = new Lazy(ClassLoader.getPlatformClassLoader(), "Tiny7");
        System.out.println("defined on demand: " + c6.getName() + " " + (c6.getClassLoader() == lazy) + " " + (lazy.loadClass("DefineCheck$Tiny6") == c6)
                           + " " + c6.getMethod("v").invoke(null) + " " + fails(() -> Class.forName("DefineCheck$Other", false, lazy))
                           + " " + (Class.forName("DefineCheck$Tiny7", false, lazy7).getClassLoader() == lazy7));
        // the ByteBuffer form, through the protected defineClass(String, ByteBuffer, ProtectionDomain):
        // a heap buffer (copied to a byte[] by the JDK) and a direct one (read from native memory)
        Loader cl2 = new Loader(scl);
        Class<?> c3 = cl2.define("DefineCheck$Tiny3", ByteBuffer.wrap(bytes("Tiny3")));
        byte[] t8 = bytes("Tiny8");
        ByteBuffer direct = ByteBuffer.allocateDirect(t8.length + 4);
        direct.putInt(0).put(t8).flip().position(4);
        Class<?> c8 = cl2.define("DefineCheck$Tiny8", direct);
        System.out.println("from a buffer: " + c3.getName() + " " + c3.getMethod("v").invoke(null) + " " + (c3.getClassLoader() == cl2)
                           + " " + c8.getName() + " " + c8.getMethod("v").invoke(null) + " " + direct.isDirect() + " " + direct.remaining());
        // Lookup.defineClass: a class of this package, strongly linked to this class's loader
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> c4 = lookup.defineClass(bytes("Tiny4"));
        System.out.println("lookup.defineClass: " + c4.getName() + " " + c4.getMethod("v").invoke(null) + " " + c4.isHidden()
                           + " " + (c4.getClassLoader() == DefineCheck.class.getClassLoader()) + " " + (Class.forName("DefineCheck$Tiny4") == c4));
        // hidden classes: a fresh one per definition, named by the VM, not findable by name, with class data
        byte[] t5 = bytes("Tiny5");
        Class<?> h1 = lookup.defineHiddenClass(t5, true).lookupClass();
        Class<?> h2 = lookup.defineHiddenClass(t5, true).lookupClass();
        System.out.println("hidden: " + h1.isHidden() + " " + (h1 != h2) + " " + h1.getName().startsWith("DefineCheck$Tiny5/0x")
                           + " " + h1.getMethod("v").invoke(null) + " " + fails(() -> Class.forName(h1.getName()))
                           + " " + (h1.getPackage() == DefineCheck.class.getPackage()) + " " + h1.getModule().isNamed()
                           + " " + (h1.getModule() == DefineCheck.class.getModule()) + " " + (h1.getClassLoader() == DefineCheck.class.getClassLoader()));
        MethodHandles.Lookup hl = lookup.defineHiddenClassWithClassData(t5, "the data", true);
        System.out.println("class data: " + MethodHandles.classData(hl, "_", String.class) + " " + hl.lookupClass().isHidden());
        Class<?> hx = lookup.defineHiddenClass(bytes("TinyEx"), true).lookupClass();
        System.out.println("hidden self-references: " + hx.getMethod("t").invoke(null)
                           + " " + (hx.getMethod("arr").invoke(null).getClass().getComponentType().getComponentType() == hx));
        // a definition whose superclass can't be found: the bytes of Orphan with its super's name changed
        byte[] orphan = bytes("Orphan");
        String sup = "DefineCheck$Tiny8", gone = "DefineCheck$Gone8";
        int at = new String(orphan, "ISO-8859-1").indexOf(sup);
        System.arraycopy(gone.getBytes("ISO-8859-1"), 0, orphan, at, sup.length());
        Loader cl3 = new Loader(scl);
        System.out.println("missing super: " + fails(() -> cl3.define("DefineCheck$Orphan", orphan)) + " " + fails(() -> Class.forName("", false, scl))
                           + " " + fails(() -> Class.forName("", false, cl3)));
        // Proxy: interfaces implemented by a generated class the JDK defines through the same native
        List<String> calls = new ArrayList<>();
        InvocationHandler h = (p, m, a) -> {
            calls.add(m.getName());
            return switch (m.getName()) {
                case "compareTo" -> 7;
                case "toString" -> "proxied";
                case "hashCode" -> 99;
                case "equals" -> a[0] == p;
                default -> null;
            };
        };
        Object p = Proxy.newProxyInstance(scl, new Class<?>[] {Runnable.class, Comparable.class}, h);
        ((Runnable) p).run();
        @SuppressWarnings("unchecked") int cmp = ((Comparable<Object>) p).compareTo("x");
        System.out.println("proxy: " + calls + " " + cmp + " " + p + " " + p.hashCode() + " " + p.equals(p) + " " + Proxy.isProxyClass(p.getClass())
                           + " " + (Proxy.getInvocationHandler(p) == h) + " " + p.getClass().getInterfaces().length
                           + " " + p.getClass().getName().startsWith("jdk.proxy") + " " + p.getClass().getModule().isNamed()
                           + " " + (p.getClass().getClassLoader() == scl) + " " + Modifier.isFinal(p.getClass().getModifiers()));
        Runnable again = (Runnable) Proxy.newProxyInstance(scl, new Class<?>[] {Runnable.class}, h);
        System.out.println("proxy classes: " + (again.getClass() != p.getClass()) + " " + Proxy.isProxyClass(again.getClass())
                           + " " + fails(() -> Proxy.newProxyInstance(scl, new Class<?>[] {String.class}, h))
                           + " " + (Class.forName(p.getClass().getName()) == p.getClass()));
    }
}
