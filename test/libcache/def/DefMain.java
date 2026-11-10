import java.lang.invoke.MethodHandles;
import java.nio.file.*;

// test/libcache/def: classes a loader of the program's own defines, cached by
// their bytes. DirLoader reads d/A and d/B from the directory given, which
// is not on the class path, and defines them. jolt test runs it over v1, v1
// again (every class restored), v2 of B alone (A restored, but its cached
// methods read B, whose bytes changed: they compile again and see v2's
// field layout and values), v2 again calling other too (compiled anew, not
// the cached code from the first run), v1, v3 of S alone (A's superclass: A
// isn't restored, since its registration lays A's fields out after S's), v1
// twice, then v2 twice calling other first (other compiled against v1 in run
// 4 was dropped with run's code in run 5, so this run compiles both anew and
// prints v2's values; before, compiling other first recorded B's new
// fingerprint and run's stale code passed the check), then v1 and v2 in "gen"
// mode, where A2 reads a class the application loader defined: it isn't
// cached, so the second run sees v2's Gen. Then "sealed" twice, where one
// loader defines the sealed d2.CS and its subclass d2.CQ (CQ restored the
// second time), and "sealed-split", where CS's loader refuses CQ and a child
// loader defines it from the same bytes: another unnamed module, so the JVM
// refuses CQ, and a CQ restored from the cache must be refused too. The
// second argument is "all", "other-first", "gen", "sealed" or "sealed-split".
public class DefMain {
    static class DirLoader extends ClassLoader {
        final Path dir;
        final String refuse;
        DirLoader(Path dir) { this(dir, DefMain.class.getClassLoader(), null); }
        DirLoader(Path dir, ClassLoader parent, String refuse) { super(parent); this.dir = dir; this.refuse = refuse; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(refuse)) throw new ClassNotFoundException(name);
            try {
                byte[] b = Files.readAllBytes(dir.resolve(name.replace('.', '/') + ".class"));
                return defineClass(name, b, 0, b.length);
            } catch (java.io.IOException e) { throw new ClassNotFoundException(name); }
        }
    }

    public static void main(String[] args) throws Exception {
        DirLoader l = new DirLoader(Path.of(args[0]));
        if (args.length > 1 && args[1].equals("gen")) {
            // Gen through the application loader (uncacheable), then A2, which
            // DirLoader defines and which extends Gen and reads its field: A2
            // must be laid out and compiled every run, since Gen's bytes can't
            // be checked
            MethodHandles.lookup().defineClass(Files.readAllBytes(Path.of(args[0], "Gen.class")));
            System.out.println(l.loadClass("A2").getMethod("run").invoke(null));
            return;
        }
        if (args.length > 1 && args[1].startsWith("sealed")) {
            ClassLoader cq = args[1].equals("sealed") ? l : new DirLoader(Path.of(args[0]), new DirLoader(Path.of(args[0]), DefMain.class.getClassLoader(), "d2.CQ"), null);
            try { System.out.println(cq.loadClass("d2.CQ").getMethod("run").invoke(null)); }
            catch (Throwable e) { System.out.println(e.getClass().getName() + ": " + e.getMessage().replaceAll("@[0-9a-f]+", "@N")); }
            return;
        }
        Class<?> a = l.loadClass("d.A");
        // "other-first" calls other before run: an uncached method of a
        // restored class first, then a cached one
        if (args.length > 1 && args[1].equals("other-first")) System.out.println(a.getMethod("other").invoke(null));
        System.out.println(a.getMethod("run").invoke(null));
        if (args.length > 1 && args[1].equals("all")) System.out.println(a.getMethod("other").invoke(null));
        System.out.println((a.getClassLoader() == l) + " " + (l.loadClass("d.B").getClassLoader() == l));
    }
}
