import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Class names as Class.forName and ClassLoader.loadClass take them (ROADMAP
// 39c, upstream OpenLDK 91050f9f, 1c68b506, bafbb88d): malformed array
// descriptors, the 255-dimension limit, slashes, and bytes found under a
// name that isn't the class's own. Each row is what HotSpot 25.0.2 gives.
public class ClassNameCheck {
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c < ' ' ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        return b.toString();
    }
    static String outcome(Throwable t) {
        return t.getClass().getName() + ": " + (t.getMessage() == null ? "null" : esc(t.getMessage()));
    }
    interface Lookup { Class<?> find(String name) throws Throwable; }
    static void row(String label, String name, Lookup l) {
        String shown = name.length() > 40 ? name.substring(0, 12) + "...(" + name.length() + " chars)" : esc(name);
        String r;
        try { r = l.find(name).getName(); if (r.length() > 40) r = r.substring(0, 12) + "...(" + r.length() + " chars)"; }
        catch (Throwable t) { r = outcome(t); if (r.length() > 120) r = r.substring(0, 60) + "...(" + r.length() + " chars)"; }
        System.out.println(label + " " + shown + " -> " + r);
    }
    static class Loader extends ClassLoader {
        Loader() { super(null); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }
    public static void main(String[] args) throws Exception {
        ClassLoader app = ClassLoader.getSystemClassLoader();
        String[] names = {"", ".", "..", "[", "[[", "[]", "[I", "[[I", "[V", "[X", "[Ljava.lang.String;",
            "[Ljava/lang/String;", "[LI;", "[L;", "[L[I;;", "[L[;", "[Ljava.lang.String;;", "[Ljava.lang.String",
            "L;", "Ljava.lang.String;", "java.lang.String;", "java.lang.String[]", "java/lang/String",
            "java/lang.String", "java.lang/String", "/java/lang/String", "int", "void",
            "java.lang.String\u0000", "[Lchampaca.names.NameFixture;"};
        for (String n : names) {
            row("forName", n, x -> Class.forName(x, false, app));
            row("loadClass", n, app::loadClass);
        }
        // the dimension limit: 255 resolves, 256 doesn't, for a primitive
        // element and a class element
        for (int d : new int[] {255, 256}) {
            row("forName", "[".repeat(d) + "I", x -> Class.forName(x, false, app));
            row("forName", "[".repeat(d) + "Ljava.lang.Object;", x -> Class.forName(x, false, app));
            row("forName null loader", "[".repeat(d) + "J", x -> Class.forName(x, false, null));
        }
        // a class's bytes found under another name: a directory class and a
        // jar class, through the application loader
        for (String n : new String[] {"champaca..names.NameFixture", "champaca/names/NameFixture",
                "champaca/names.NameFixture", "champaca.names/NameFixture", "champaca.names.NameFixture",
                "champaca.names..NameFixture", "champaca...names.NameFixture", ".champaca.names.NameFixture",
                "/champaca/names/NameFixture", "..champaca.names.NameFixture", "champaca.names.NameFixture.",
                "champaca..lib.Greeter", "champaca/lib/Greeter", "champaca/lib.Greeter", "champaca.lib.Greeter",
                ".champaca.lib.Greeter"}) {
            row("loadClass", n, app::loadClass);
            row("forName", n, x -> Class.forName(x, false, app));
        }
        // a class file under another file name in a class path directory
        Path dir = Path.of(app.getResource("champaca/names/NameFixture.class").toURI()).getParent();
        Path renamed = dir.resolve("Renamed.class");
        Files.copy(dir.resolve("NameFixture.class"), renamed, StandardCopyOption.REPLACE_EXISTING);
        try {
            row("loadClass", "champaca.names.Renamed", app::loadClass);
            row("forName", "champaca.names.Renamed", x -> Class.forName(x, false, app));
        } finally {
            Files.delete(renamed);
        }
        // defineClass with a name that isn't the bytes' own
        byte[] bytes;
        try (InputStream in = app.getResourceAsStream("champaca/names/NameFixture.class")) { bytes = in.readAllBytes(); }
        Loader own = new Loader();
        row("defineClass", "champaca.names.Wrong", x -> own.define(x, bytes));
        row("defineClass", "champaca/names/NameFixture", x -> own.define(x, bytes));
    }
}
