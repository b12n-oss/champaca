import java.io.InputStream;

// The checks a JVM makes when it defines a class (JVMS 5.3.5, 5.4.5), with
// HotSpot's errors and texts, for a class loaded from the class path and for
// one defined from its bytes: a final superclass, an interface as superclass,
// a class among the interfaces, an override of a final method, an interface
// whose superclass isn't Object, a class extending itself; two it accepts;
// and an instruction naming a refused class. The class files come from
// test/gen/ClassDefGen.java, since javac won't compile them.
public class ClassDefCheck {
    static class Loader extends ClassLoader {
        Class<?> define(String name) throws Exception {
            try (InputStream in = ClassDefCheck.class.getResourceAsStream("/cdbytes/" + name + ".bin")) {
                byte[] b = in.readAllBytes();
                return defineClass(name, b, 0, b.length);
            }
        }
        // the self-extending twin's superclass is itself, asked of this loader
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("CdSelfB")) { try { return define(name); } catch (Exception e) { throw new ClassNotFoundException(name, e); } }
            return super.loadClass(name, resolve);
        }
    }
    static String outcome(java.util.concurrent.Callable<Class<?>> c) {
        try { Class<?> k = c.call(); k.getDeclaredMethods(); return "defined " + k.getName(); }
        catch (Throwable e) { return e.getClass().getName() + ": " + e.getMessage(); }
    }
    public static void main(String[] args) throws Exception {
        for (String n : new String[] {"CdFinalChild", "CdInterfaceSuper", "CdClassInterface", "CdFinalOverride", "CdIfaceWithSuper", "CdSelf", "CdPrivateFinal", "CdStaticBeside"}) {
            System.out.println(n + " from the class path: " + outcome(() -> Class.forName(n)));
            System.out.println(n + " again: " + outcome(() -> Class.forName(n)));
            System.out.println(n + "B from its bytes: " + outcome(() -> new Loader().define(n + "B")));
        }
        // an instruction naming a refused class throws its error there, each time
        Class<?> user = Class.forName("CdUser");
        for (int i = 0; i < 2; i++) {
            try { System.out.println("new CdFinalChild: " + user.getMethod("make").invoke(null)); }
            catch (java.lang.reflect.InvocationTargetException e) { System.out.println("new CdFinalChild: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage()); }
        }
    }
}
