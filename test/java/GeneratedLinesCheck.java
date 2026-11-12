import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

// A class defined from bytes with no code source, as ClassLoader.defineClass(name, b, off, len)
// gives and a Clojure REPL's eval classes have: its frames carry line numbers, as on HotSpot.
// Gen is never named in code, so the only class of that name is the one defined here.
public class GeneratedLinesCheck {
    public static class Gen {
        public static void outer() {
            inner();
        }
        static void inner() {
            throw new IllegalStateException("from generated code");
        }
        public static int depth() {
            return new Throwable().getStackTrace()[0].getLineNumber();
        }
    }

    static class Loader extends ClassLoader {
        Loader() { super(GeneratedLinesCheck.class.getClassLoader()); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }

    public static void main(String[] args) throws Exception {
        byte[] b;
        try (InputStream in = GeneratedLinesCheck.class.getResourceAsStream("GeneratedLinesCheck$Gen.class")) {
            b = in.readAllBytes();
        }
        Class<?> c = new Loader().define("GeneratedLinesCheck$Gen", b);
        System.out.println("code source: " + c.getProtectionDomain().getCodeSource());
        System.out.println("own line: " + c.getMethod("depth").invoke(null));
        try {
            c.getMethod("outer").invoke(null);
        } catch (InvocationTargetException e) {
            for (StackTraceElement f : e.getCause().getStackTrace()) {
                if (f.getClassName().equals("GeneratedLinesCheck$Gen")) {
                    System.out.println(f.getMethodName() + " " + f.getFileName() + ":" + f.getLineNumber());
                }
            }
        }
    }
}
