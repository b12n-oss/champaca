import java.io.*;
import java.net.*;
// JDK class files as resources: getResource and getResources give the JVM's
// jrt: URLs through every loader (system, application, platform, a class's
// own, a URLClassLoader with and without a parent, the module), and the
// streams and URLs open to the class file. A JDK resource that isn't a
// class file has no URL, as on the JVM.
public class JrtResourceCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String l, Callable c) { try { System.out.println(l + ": " + c.call()); } catch (Throwable e) { System.out.println(l + ": " + e); } }
    static String len(InputStream in) throws IOException { if (in == null) return "null"; try (in) { byte[] b = in.readAllBytes(); return b.length > 1000 && (b[0] & 0xff) == 0xca ? "class bytes" : "bytes " + b.length; } }
    public static void main(String[] a) {
        t("system stream", () -> len(ClassLoader.getSystemResourceAsStream("java/lang/reflect/AccessibleObject.class")));
        t("system url", () -> String.valueOf(ClassLoader.getSystemResource("java/lang/Object.class")));
        t("app loader stream", () -> len(JrtResourceCheck.class.getClassLoader().getResourceAsStream("java/lang/Object.class")));
        t("app loader url", () -> String.valueOf(JrtResourceCheck.class.getClassLoader().getResource("java/lang/Object.class")));
        t("class stream", () -> len(Object.class.getResourceAsStream("Object.class")));
        t("class url", () -> String.valueOf(Object.class.getResource("Object.class")));
        t("class abs stream", () -> len(JrtResourceCheck.class.getResourceAsStream("/java/lang/String.class")));
        t("platform stream", () -> len(ClassLoader.getPlatformClassLoader().getResourceAsStream("java/sql/Connection.class")));
        t("platform url", () -> String.valueOf(ClassLoader.getPlatformClassLoader().getResource("java/sql/Connection.class")));
        t("url open", () -> len(ClassLoader.getSystemResource("java/lang/Object.class").openStream()));
        t("resources enum", () -> java.util.Collections.list(ClassLoader.getSystemResources("java/lang/Object.class")).size());
        t("jrt url", () -> len(new URL("jrt:/java.base/java/lang/Object.class").openStream()));
        t("module stream", () -> len(Object.class.getModule().getResourceAsStream("java/lang/Object.class")));
        t("url loader, no parent", () -> String.valueOf(new URLClassLoader(new URL[0], null).getResource("java/lang/String.class")));
        t("url loader, no parent, list", () -> java.util.Collections.list(new URLClassLoader(new URL[0], null).getResources("java/util/List.class")));
        t("url loader under the app", () -> String.valueOf(new URLClassLoader(new URL[0]).getResource("java/util/Map.class")));
        t("platform list", () -> java.util.Collections.list(ClassLoader.getPlatformClassLoader().getResources("java/sql/Date.class")));
        t("not a class", () -> String.valueOf(ClassLoader.getSystemResource("java/lang/uniName.dat")));
        t("missing class", () -> String.valueOf(ClassLoader.getSystemResource("java/lang/Nope.class")));
        t("own url", () -> JrtResourceCheck.class.getResource("JrtResourceCheck.class").getProtocol());
    }
}
