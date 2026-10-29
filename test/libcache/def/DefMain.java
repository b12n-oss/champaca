import java.nio.file.*;

// test/libcache/def: classes a loader of the program's own defines, cached by
// their bytes. DirLoader reads d/A and d/B from the directory given, which
// is not on the class path, and defines them. bb test runs it over v1, v1
// again (A and B restored), v2 of B alone (A restored, but its cached
// methods read B, whose bytes changed: they compile again and see v2's
// field layout and values), then v1 twice.
public class DefMain {
    static class DirLoader extends ClassLoader {
        final Path dir;
        DirLoader(Path dir) { super(DefMain.class.getClassLoader()); this.dir = dir; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                byte[] b = Files.readAllBytes(dir.resolve(name.replace('.', '/') + ".class"));
                return defineClass(name, b, 0, b.length);
            } catch (java.io.IOException e) { throw new ClassNotFoundException(name); }
        }
    }

    public static void main(String[] args) throws Exception {
        DirLoader l = new DirLoader(Path.of(args[0]));
        Class<?> a = l.loadClass("d.A");
        System.out.println(a.getMethod("run").invoke(null));
        System.out.println((a.getClassLoader() == l) + " " + (l.loadClass("d.B").getClassLoader() == l));
    }
}
