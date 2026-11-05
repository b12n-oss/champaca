import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
        String shown = name.length() > 40 ? esc(name.substring(0, 12)) + "...(" + name.length() + " chars)" : esc(name);
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
        // through the boot loader, the message is the slashed name
        for (String n : new String[] {"no.Such", "[Lno.Such;", "[[Lno.Such;", "champaca.names.NameFixture", "java.lang.String", "[Ljava.lang.Object;"})
            row("forName null loader", n, x -> Class.forName(x, false, null));
        // the platform loader reads no class path, whatever the name
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        for (String n : new String[] {"champaca/names/NameFixture", "champaca..names.NameFixture", "champaca.names.NameFixture"})
            row("platform loadClass", n, platform::loadClass);
        // a name over 65535 bytes of modified UTF-8, its message abridged
        for (String n : new String[] {"a.".repeat(32767) + "b", "a.".repeat(32767) + "bc", "\u00e9".repeat(32768),
                "\u4e2d".repeat(21846), "\ud83d\ude00".repeat(10923), "\u0000".repeat(32768)}) {
            row("long forName", n, x -> { try { return Class.forName(x, false, app); }
                catch (ClassNotFoundException e) { throw new ClassNotFoundException("length " + e.getMessage().length() + " hash " + e.getMessage().hashCode()); } });
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
        byte[] fixture = Files.readAllBytes(dir.resolve("NameFixture.class"));
        Files.write(renamed, fixture);
        // a class file cut short, and one from a newer release, found
        // through a name with an empty segment or a slash
        Path cut = dir.resolve("Cut.class");
        Files.write(cut, java.util.Arrays.copyOf(fixture, 20));
        Path future = dir.resolve("Future.class");
        byte[] newer = fixture.clone();
        newer[6] = 0; newer[7] = 70;
        Files.write(future, newer);
        try {
            row("loadClass", "champaca.names.Renamed", app::loadClass);
            row("forName", "champaca.names.Renamed", x -> Class.forName(x, false, app));
            for (String n : new String[] {"champaca.names..Cut", "champaca/names/Cut", "champaca.names.Cut",
                    "champaca.names..Future", "champaca/names/Future", "champaca.names.Future"})
                row("loadClass", n, app::loadClass);
        } finally {
            Files.delete(renamed);
            Files.delete(cut);
            Files.delete(future);
        }
        // defineClass with a name that isn't the bytes' own
        byte[] bytes;
        try (InputStream in = app.getResourceAsStream("champaca/names/NameFixture.class")) { bytes = in.readAllBytes(); }
        Loader own = new Loader();
        row("defineClass", "champaca.names.Wrong", x -> own.define(x, bytes));
        row("defineClass", "champaca/names/NameFixture", x -> own.define(x, bytes));
    }
}
