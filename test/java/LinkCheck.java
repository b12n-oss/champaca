import java.io.*;
import java.util.*;
import java.util.function.*;

// A JVM resolves a class an instruction names only when that instruction
// runs (JVMS 5.4.3), so code behind a check such as Spring's
// ClassUtils.isPresent may name a class that isn't there. The Gone classes
// below are deleted before anything uses them; each instruction kind is in a
// method of its own, as each method is translated whole. Then a loader that
// defines its classes only from findClass, as ABCL's MemoryClassLoader does:
// a class it defined names another of its classes, which only it can
// resolve. After upstream OpenLDK's testsuite/aaa linkmissing, ldcmissing and
// lazyloader.
public class LinkCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable0 c) {
        try { return "ran (" + c.call() + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    interface Callable0 { Object call() throws Throwable; }

    static boolean present = false;

    static Object newInstance() { return present ? new LinkGone() : "absent"; }
    static Object checkcast(Object o) { return present ? (LinkGone) o : "absent"; }
    static Object instanceOf(Object o) { return present ? (Object) (o instanceof LinkGone) : "absent"; }
    // the array isn't printed: toString would load the element class anyway
    static Object newArray() { return present ? (new LinkGone[1] != null ? "made" : "null") : "absent"; }
    static Object getStatic() { return present ? LinkGone.FIELD : "absent"; }
    static Object putStatic() { if (present) LinkGone.FIELD = "x"; return "absent"; }
    static Object invokeStatic() { return present ? LinkGone.make() : "absent"; }
    static Object invokeVirtual(Object o) { return present ? ((LinkGone) o).name() : "absent"; }
    static Object ldc() { return present ? LinkGone.class : "absent"; }
    static Object subclass() { return present ? new LinkOrphan() : "absent"; }
    // the error caught by a handler of the same method
    static Object caughtHere() {
        try { return present ? new LinkGone() : "absent"; }
        catch (NoClassDefFoundError e) { return "caught here: " + e.getMessage(); }
    }
    static Object orphanArray() { return present ? new LinkOrphan[0][0] : "absent"; }
    static Object catchType() {
        try {
            if (present) throw new IllegalStateException("thrown");
            return "absent";
        } catch (LinkGoneException e) {
            return "caught";
        }
    }
    static Object lambda() {
        if (!present) return "absent";
        Supplier<LinkGone> s = LinkGone::new;
        return s.get();
    }
    static Object lambdaInterface() {
        if (!present) return "absent";
        LinkGoneListener l = (a, b) -> { };
        return l;
    }
    // a present class whose members' types are missing
    LinkGone gone;
    static LinkGone takeGone(LinkGone g) { return g; }
    Object fieldOfMissingType() { if (present) gone = null; return present ? gone : "absent"; }
    static Object paramOfMissingType() { return present ? takeGone(null) : "absent"; }

    static final Map<String, Callable0> PROBES = new LinkedHashMap<>();
    static {
        PROBES.put("new", LinkCheck::newInstance);
        PROBES.put("checkcast", () -> checkcast(null));
        PROBES.put("instanceof", () -> instanceOf(null));
        PROBES.put("anewarray", LinkCheck::newArray);
        PROBES.put("getstatic", LinkCheck::getStatic);
        PROBES.put("putstatic", LinkCheck::putStatic);
        PROBES.put("invokestatic", LinkCheck::invokeStatic);
        PROBES.put("invokevirtual", () -> invokeVirtual(null));
        PROBES.put("ldc", LinkCheck::ldc);
        PROBES.put("superclass", LinkCheck::subclass);
        PROBES.put("handler", LinkCheck::caughtHere);
        PROBES.put("catch", LinkCheck::catchType);
        PROBES.put("lambda", LinkCheck::lambda);
        PROBES.put("lambda interface", LinkCheck::lambdaInterface);
        PROBES.put("field type", () -> new LinkCheck().fieldOfMissingType());
        PROBES.put("parameter type", LinkCheck::paramOfMissingType);
    }

    // defines LinkLazy$Hid* from the LinkLazy$Gen* class files, renamed, and
    // only when findClass asks; nothing else can load a Hid class
    public static class LinkLazy {
        public static class GenB { public static int VALUE = 41 + 1; }
        public static class GenA implements Supplier<String> {
            public String get() { return "A sees " + GenB.VALUE; }
        }
        public static class GenBase { public String base() { return "base"; } }
        public static class GenC extends GenBase implements Supplier<String> {
            public String get() { return "C extends " + base(); }
        }
        public static class GenBad { }
        public static class GenD implements Supplier<String> {
            public String get() { return "D made " + new GenBad(); }
        }
        // ldc of a class only the loader can see, and of an array of it
        public static class GenF { }
        // members typed by classes only the loader can see
        public static class GenH { }
        // an annotation whose Class value only the loader can see (the
        // JDK's AnnotationParser resolves it in Java, through the loader)
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        public @interface GenTag { Class<?> value(); }
        @GenTag(GenH.class) public static class GenK { }
        public static class GenG {
            public GenH h;
            public GenG() { }
            public GenG(GenH h) { this.h = h; }
            public GenH swap(GenH x) { return x; }
        }
        public static class GenE implements Supplier<String> {
            public String get() { return "E names " + GenF.class.getName() + " " + GenF[].class.getName(); }
        }
    }
    static class Lazy extends ClassLoader {
        final List<String> asked = new ArrayList<>();
        Lazy() { super(LinkCheck.class.getClassLoader()); }
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            asked.add(name.substring(name.lastIndexOf('$') + 1));
            if (name.endsWith("$HidBad")) throw new ClassFormatError("bad bytes for " + name);
            String prefix = "LinkCheck$LinkLazy$Hid";
            if (!name.startsWith(prefix)) throw new ClassNotFoundException(name);
            try (InputStream in = LinkCheck.class.getResourceAsStream("LinkCheck$LinkLazy$Gen" + name.substring(prefix.length()) + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] b = in.readAllBytes();
                byte[] from = "LinkLazy$Gen".getBytes(), to = "LinkLazy$Hid".getBytes();
                outer:
                for (int i = 0; i + from.length <= b.length; i++) {
                    for (int j = 0; j < from.length; j++) if (b[i + j] != from[j]) continue outer;
                    System.arraycopy(to, 0, b, i, to.length);
                }
                return defineClass(name, b, 0, b.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
    @SuppressWarnings("unchecked")
    static String lazy(ClassLoader l, String name) throws Exception {
        Class<?> c = l.loadClass("LinkCheck$LinkLazy$" + name);
        return (c.getClassLoader() == l) + " " + ((Supplier<String>) c.getDeclaredConstructor().newInstance()).get();
    }

    // ldc of a class resolves when it runs: a class defined after the method
    // was first run is found, and one that can't load fails only there
    static Object ldcLater(boolean go) { return go ? LinkLaterHid.class : "not called"; }
    static Object ldcBroken(boolean go) { return go ? LinkBroken.class : "not called"; }

    public static void main(String[] args) throws Exception {
        for (String n : List.of("LinkGone", "LinkGoneException", "LinkGoneListener", "LinkLaterHid"))
            new File(LinkCheck.class.getResource(n + ".class").toURI()).delete();
        File broken = new File(LinkCheck.class.getResource("LinkBroken.class").toURI());
        byte[] whole = java.nio.file.Files.readAllBytes(broken.toPath());
        java.nio.file.Files.write(broken.toPath(), Arrays.copyOf(whole, 20));
        row("ldc later not run", ldcLater(false));
        byte[] later;
        try (InputStream in = LinkCheck.class.getResourceAsStream("LinkLaterGen.class")) { later = in.readAllBytes(); }
        byte[] gen = "LinkLaterGen".getBytes(), hid = "LinkLaterHid".getBytes();
        outer:
        for (int i = 0; i + gen.length <= later.length; i++) {
            for (int j = 0; j < gen.length; j++) if (later[i + j] != gen[j]) continue outer;
            System.arraycopy(hid, 0, later, i, hid.length);
        }
        java.lang.invoke.MethodHandles.lookup().defineClass(later);
        row("ldc later run", ldcLater(true));
        // (run, it is the JVM's ClassFormatError; champaca's load failure
        // isn't a Java error yet, so the row stops at not run)
        row("ldc broken not run", ldcBroken(false));
        for (Map.Entry<String, Callable0> p : PROBES.entrySet()) row(p.getKey() + " not run", fails(p.getValue()));
        present = true;
        for (Map.Entry<String, Callable0> p : PROBES.entrySet()) row(p.getKey() + " run", fails(p.getValue()));
        // the failed resolution is the same error a second time
        row("new again", fails(LinkCheck::newInstance));
        try { newInstance(); } catch (NoClassDefFoundError e) { row("cause", e.getCause()); }
        row("array of orphans", fails(LinkCheck::orphanArray));
        row("forName", fails(() -> Class.forName("LinkGone")));

        Lazy l = new Lazy();
        row("lazy reference", lazy(l, "HidA"));
        row("lazy superclass", lazy(l, "HidC"));
        row("lazy forName", Class.forName("LinkCheck$LinkLazy$HidB", true, l).getClassLoader() == l);
        row("lazy missing", fails(() -> Class.forName("LinkCheck$LinkLazy$HidZ", true, l)));
        row("lazy loader error", fails(() -> lazy(l, "HidD")));
        row("lazy class constant", lazy(l, "HidE"));
        Class<?> g = l.loadClass("LinkCheck$LinkLazy$HidG");
        row("lazy member types", g.getDeclaredField("h").getType().getName() + " "
            + g.getMethod("swap", g.getDeclaredField("h").getType()).getReturnType().getName() + " "
            + g.getDeclaredConstructors().length);
        java.lang.annotation.Annotation[] tags = l.loadClass("LinkCheck$LinkLazy$HidK").getAnnotations();
        row("lazy annotation class value", tags.length + " " + tags[0].annotationType().getName() + " "
            + ((Class<?>) tags[0].annotationType().getMethod("value").invoke(tags[0])).getName());
        row("lazy asked", l.asked);
        row("lazy not ours", fails(() -> lazy(new Lazy(), "GenA")));
    }
}

class LinkGone {
    static Object FIELD = "f";
    static LinkGone make() { return new LinkGone(); }
    String name() { return "gone"; }
}
class LinkGoneException extends RuntimeException { }
interface LinkGoneListener { void handle(long a, long b); }
class LinkOrphan extends LinkGone { }
class LinkLaterGen { }
class LinkLaterHid { }
class LinkBroken { }
