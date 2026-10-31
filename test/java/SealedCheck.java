import java.io.InputStream;
import java.util.Arrays;

// Sealed classes and interfaces (JVMS 4.7.31, 5.3.5): Class.isSealed and
// getPermittedSubclasses for javac's sealed types, for generated ones listing
// a class that's nowhere or isn't a subclass, and for the JDK's; and the
// subclasses a JVM refuses to define, from the class path, again, and from
// bytes, with HotSpot's errors and texts (a loader's @hash masked). The
// refused class files come from test/gen/SealedGen.java.
public class SealedCheck {
    sealed interface Shape permits Circle, Square, Poly {}
    record Circle(double r) implements Shape {}
    record Square(double s) implements Shape {}
    static non-sealed class Poly implements Shape {}
    static class Tri extends Poly {}
    abstract static sealed class Node permits Leaf, Pair {}
    static final class Leaf extends Node {}
    static final class Pair extends Node {}

    static String area(Shape s) {
        return switch (s) {
            case Circle c -> "circle " + c.r();
            case Square q -> "square " + q.s();
            case Poly p -> "poly " + p.getClass().getSimpleName();
        };
    }
    // a loader of the program's own, defining sgbytes/<name>.bin
    static class Loader extends ClassLoader {
        Loader(String name) { super(name, SealedCheck.class.getClassLoader()); }
        // the bytes of sgbytes/<file>.bin defined under another name
        Class<?> defineAs(String name, String file) throws Exception {
            try (InputStream in = SealedCheck.class.getResourceAsStream("/sgbytes/" + file + ".bin")) {
                byte[] b = in.readAllBytes();
                return defineClass(name, b, 0, b.length);
            }
        }
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try (InputStream in = SealedCheck.class.getResourceAsStream("/sgbytes/" + name + ".bin")) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] b = in.readAllBytes();
                return defineClass(name, b, 0, b.length);
            } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
        }
    }
    static String outcome(java.util.concurrent.Callable<Class<?>> c) {
        try { Class<?> k = c.call(); k.getDeclaredMethods(); return "defined " + k.getName(); }
        catch (Throwable e) { return e.getClass().getName() + ": " + String.valueOf(e.getMessage()).replaceAll("@[0-9a-f]+", "@N").replaceAll("0x[0-9a-f]+", "0xN"); }
    }
    static String perm(Class<?> c) {
        Class<?>[] p = c.getPermittedSubclasses();
        return c.getName() + " sealed=" + c.isSealed() + " permits=" + (p == null ? "null" : Arrays.toString(p));
    }
    public static void main(String[] args) throws Exception {
        for (Shape s : new Shape[] {new Circle(1.5), new Square(2), new Poly(), new Tri()}) System.out.println(area(s));
        for (Class<?> c : new Class<?>[] {Shape.class, Circle.class, Poly.class, Tri.class, Node.class, Leaf.class,
                                          Object.class, int[].class, int.class, Shape[].class})
            System.out.println(perm(c));
        System.out.println(perm(java.lang.constant.ConstantDesc.class));
        System.out.println(perm(java.lang.constant.ClassDesc.class));
        for (String n : new String[] {"SgSealed", "SgSealedI", "SgEmpty", "SgA", "SgSubI", "SgSealed4"})
            System.out.println(perm(Class.forName(n)));
        for (String n : new String[] {"SgNotListed", "SgNotListedI", "SgNotListedJ", "SgFromEmpty", "sgp.SgOther",
                                      "sgp.q.SgPkgOther", "SgPkgSame", "SgOrder", "SgJdk", "SgFinalSealed"}) {
            System.out.println(n + ": " + outcome(() -> Class.forName(n)));
            System.out.println(n + " again: " + outcome(() -> Class.forName(n)));
        }
        for (String n : new String[] {"SgNotListedB", "SgNotListedIB", "SgNotListedJB", "SgFromEmptyB", "SgFinalSealedB", "SgOkB"})
            System.out.println(n + " from its bytes: " + outcome(() -> Class.forName(n, true, new Loader(null))));
        // a wrong name is found before the final class's attribute
        System.out.println("wrong name: " + outcome(() -> new Loader(null).defineAs("SgWrongName", "SgFinalSealedB")));
        try (InputStream in = SealedCheck.class.getResourceAsStream("/sgbytes/SgHidden.bin")) {
            byte[] b = in.readAllBytes();
            System.out.println("hidden: " + outcome(() -> java.lang.invoke.MethodHandles.lookup().defineHiddenClass(b, true).lookupClass()));
        }
        // one loader defines the sealed class and its subclasses, unnamed and named
        for (String[] lk : new String[][] {{null, "5"}, {"mine", "7"}}) {
            Loader own = new Loader(lk[0]);
            String k = lk[1];
            // the named round asks for the list first: its names resolve through own
            if (lk[0] != null) System.out.println("loader " + lk[0] + " first: " + perm(Class.forName("SgSealed" + k + "B", true, own)));
            System.out.println("loader " + lk[0] + ", other package: " + outcome(() -> Class.forName("sgr.s.SgSub" + k + "B", true, own)));
            System.out.println("loader " + lk[0] + ", listed: " + outcome(() -> Class.forName("SgOk" + k + "B", true, own)));
            System.out.println("loader " + lk[0] + ": " + perm(Class.forName("SgSealed" + k + "B", true, own)));
        }
    }
}
