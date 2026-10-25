import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

// Resources through class loaders of a program's own (URLClassLoader over a
// jar and a directory, with no parent and with the application loader as
// parent), a class such a loader defines finding its own resources, the
// application loader's directory resources, and code sources and protection
// domains. Every value printed is the same on any machine: no paths.
// Upstream OpenLDK's testsuite/aaa jardirs, jarscan and codesource ask the
// same of Spring's scanning, the webjars locator and Quarkus's launcher.
public class ResourceCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String text(InputStream in) throws IOException {
        if (in == null) return "null";
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    static String found(URL u) { return u == null ? "null" : u.getProtocol(); }
    static int count(Enumeration<URL> e) { return Collections.list(e).size(); }
    static class Row { }

    // a copy of a class's bytes under a name of the same length
    static byte[] renamed(byte[] b, String from, String to) {
        byte[] f = from.getBytes(StandardCharsets.UTF_8), t = to.getBytes(StandardCharsets.UTF_8);
        b = b.clone();
        outer:
        for (int i = 0; i + f.length <= b.length; i++) {
            for (int j = 0; j < f.length; j++) if (b[i + j] != f[j]) continue outer;
            System.arraycopy(t, 0, b, i, t.length);
        }
        return b;
    }

    static void probeLoader(String name, ClassLoader l) throws Exception {
        row(name + " getResource", found(l.getResource("d/a.txt")) + " " + found(l.getResource("d/"))
            + " " + found(l.getResource("d")) + " " + found(l.getResource("d/none")) + " " + found(l.getResource("/d/a.txt")));
        row(name + " getResourceAsStream", text(l.getResourceAsStream("d/a.txt")) + " " + text(l.getResourceAsStream("d/none")));
        row(name + " getResources", count(l.getResources("d/a.txt")) + " " + count(l.getResources("d/none"))
            + " " + count(l.getResources("shared.txt")));
        if (l instanceof URLClassLoader u)
            row(name + " findResource", found(u.findResource("d/a.txt")) + " " + found(u.findResource("shared.txt")));
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("resourcecheck");
        Path jar = tmp.resolve("r.jar");
        byte[] probe;
        // ResourceProbe lives in the jar only, so the loaders below define it
        try (InputStream in = ResourceCheck.class.getResourceAsStream("ResourceProbe.class")) { probe = in.readAllBytes(); }
        new File(ResourceCheck.class.getResource("ResourceProbe.class").toURI()).delete();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String[] e : new String[][] {{"d/", null}, {"d/a.txt", "in the jar"}, {"shared.txt", "jar copy"}, {"probe.txt", "probe's own"},
                                              {"META-INF/", null}, {"META-INF/x.txt", "x"}, {"odd/a=b;c d\u00e9#.txt", "odd"}}) {
                out.putNextEntry(new JarEntry(e[0]));
                if (e[1] != null) out.write(e[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            // a name the application loader has too
            out.putNextEntry(new JarEntry("ResourceCheck.class"));
            out.write(1);
            out.closeEntry();
            out.putNextEntry(new JarEntry("ResourceProbe.class"));
            out.write(probe);
            out.closeEntry();
        }
        Path dir = Files.createDirectories(tmp.resolve("dir"));
        Files.createDirectories(dir.resolve("d"));
        Files.writeString(dir.resolve("d/a.txt"), "in the directory");
        Files.writeString(dir.resolve("a=b;c d\u00e9#.txt"), "odd");
        URL jarUrl = jar.toUri().toURL();

        // a loader with no parent, over a jar, then a directory
        try (URLClassLoader own = new URLClassLoader(new URL[] {jarUrl}, null)) {
            probeLoader("jar, no parent", own);
            // a directory entry keeps its /, whatever the host's disk has; names
            // are escaped as the JDK's class path escapes them
            URL meta = own.getResource("META-INF/");
            String odd = own.getResource("odd/a=b;c d\u00e9#.txt").toString();
            row("jar URL names", own.getResource("d/").toString().endsWith("!/d/") + " " + own.getResource("d").toString().endsWith("!/d")
                + " " + meta.toString().endsWith("!/META-INF/") + " " + odd.substring(odd.indexOf("!/"))
                + " " + text(own.getResource("odd/a=b;c d\u00e9#.txt").openStream()));
            Class<?> p = own.loadClass("ResourceProbe");
            Object o = p.getDeclaredConstructor().newInstance();
            row("defined class", p.getClassLoader() == own);
            row("defined class resources", p.getMethod("own").invoke(o) + " | " + p.getMethod("relative").invoke(o)
                + " " + p.getMethod("absolute").invoke(o));
            CodeSource cs = p.getProtectionDomain().getCodeSource();
            row("defined class code source", (cs != null) + " " + (cs != null && jarUrl.equals(cs.getLocation()))
                + " " + (p.getProtectionDomain().getClassLoader() == own));
        }
        try (URLClassLoader own = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            row("directory, no parent", text(own.getResourceAsStream("d/a.txt")) + " " + found(own.getResource("d"))
                + " " + found(own.getResource("d/")) + " " + count(own.getResources("d/a.txt")));
            String odd = own.getResource("a=b;c d\u00e9#.txt").toString();
            row("directory URL names", odd.substring(odd.lastIndexOf('/')) + " " + own.getResource("d").toString().endsWith("/d"));
        }
        // the platform loader as parent: nothing of the application's
        try (URLClassLoader iso = new URLClassLoader(new URL[] {jarUrl}, ClassLoader.getPlatformClassLoader())) {
            row("platform parent", found(iso.getResource("ResourceCheck.class")) + " " + count(iso.getResources("ResourceCheck.class"))
                + " " + (iso.getResourceAsStream("ResourceCheck.class") != null) + " " + text(iso.getResourceAsStream("d/a.txt")));
            ClassLoader pl = ClassLoader.getPlatformClassLoader();
            row("platform loader", found(pl.getResource("ResourceCheck.class")) + " " + pl.getResourceAsStream("ResourceCheck.class")
                + " " + count(pl.getResources("ResourceCheck.class")));
        }
        // the application loader as parent: its resources first
        try (URLClassLoader child = new URLClassLoader(new URL[] {jarUrl}, ResourceCheck.class.getClassLoader())) {
            probeLoader("jar, app parent", child);
            List<String> both = new ArrayList<>();
            for (URL u : Collections.list(child.getResources("ResourceCheck.class"))) both.add(u.getProtocol());
            row("parent first", found(child.getResource("ResourceCheck.class")) + " " + both
                + " " + found(child.findResource("ResourceCheck.class")));
        }
        // a loader of the program's own that overrides findResource only
        ClassLoader custom = new ClassLoader(null) {
            protected URL findResource(String name) {
                try { return name.equals("magic.txt") ? jar.resolve("x").toUri().toURL() : null; }
                catch (MalformedURLException e) { throw new UncheckedIOException(e); }
            }
        };
        // its URL names a file that isn't there: no stream, as the JDK's IOException
        row("custom findResource", found(custom.getResource("magic.txt")) + " " + found(custom.getResource("other.txt"))
            + " " + custom.getResourceAsStream("magic.txt"));

        // the application loader: a directory entry's root and its directories
        ClassLoader app = ResourceCheck.class.getClassLoader();
        URL root = app.getResource("");
        row("app root", found(root) + " " + (root != null && root.getPath().endsWith("/"))
            + " " + (app.getResourceAsStream("") != null));
        // names the class path itself escapes: target/lib.jar has these (test/jar)
        URL oddApp = app.getResource("odd/a=b;c d\u00e9#.txt");
        row("app jar URL names", (oddApp == null ? "null" : oddApp.toString().substring(oddApp.toString().indexOf("!/")))
            + " " + (oddApp == null ? "" : text(oddApp.openStream()))
            + " " + String.valueOf(app.getResource("champaca/lib/")).endsWith("!/champaca/lib/")
            + " " + String.valueOf(app.getResource("champaca/lib")).endsWith("!/champaca/lib"));
        row("app class resource", found(app.getResource("ResourceCheck.class")) + " " + found(ClassLoader.getSystemResource("ResourceCheck.class")));

        // code sources
        CodeSource mine = ResourceCheck.class.getProtectionDomain().getCodeSource();
        row("code source", (mine != null) + " " + (mine != null && mine.getLocation().getProtocol().equals("file"))
            + " " + (mine != null && root != null && mine.getLocation().equals(root)));
        ProtectionDomain pd = ResourceCheck.class.getProtectionDomain();
        row("protection domain", (pd.getClassLoader() == app) + " " + (pd == Row.class.getProtectionDomain())
            + " " + String.class.getProtectionDomain().getCodeSource());

        // a protection domain given to defineClass is the class's
        ProtectionDomain given = new ProtectionDomain(new CodeSource(jarUrl, (CodeSigner[]) null), null);
        ClassLoader definer = new ClassLoader(null) {
            Class<?> define(byte[] b, ProtectionDomain d) { return defineClass("ResourceProbX", b, 0, b.length, d); }
            { row("given protection domain", define(renamed(probe, "ResourceProbe", "ResourceProbX"), given).getProtectionDomain() == given); }
        };
        row("definer", definer.getParent());

        try (var w = Files.walk(tmp)) { w.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); }
    }
}

