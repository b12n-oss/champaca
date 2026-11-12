import java.util.*;

// Run as `-jar target/app.jar` by jolt test: Main-Class names this class, and
// the manifest's Class-Path names lib.jar beside it, whose classes, services
// and code source come through it as on the JVM. java.class.path is the jar
// alone, as java -jar has it. The manifest's Add-Opens and Add-Exports open
// java.lang and export jdk.internal.misc to the class path, as the java
// launcher applies them for the main jar; java.util stays closed.
public class JarMain {
    public static void main(String[] args) throws Exception {
        System.out.println("args: " + Arrays.toString(args));
        System.out.println("class path: " + System.getProperty("java.class.path").replace('\\', '/').endsWith("target/app.jar"));
        System.out.println("own code source: " + JarMain.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/target/app.jar"));
        Class<?> note = Class.forName("champaca.lib.Note");
        System.out.println("from Class-Path: " + note.getName() + " " + note.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/target/lib.jar"));
        for (Object g : ServiceLoader.load(Class.forName("champaca.lib.Greeter")))
            System.out.println("service: " + g.getClass().getName() + " " + g.getClass().getMethod("greet", String.class).invoke(g, "jar"));
        System.out.println("Add-Opens: " + java.lang.invoke.MethodHandles.privateLookupIn(Thread.class, java.lang.invoke.MethodHandles.lookup()).lookupClass().getName()
            + " " + Object.class.getModule().isOpen("java.lang", JarMain.class.getModule()));
        System.out.println("Add-Exports: " + Class.forName("jdk.internal.misc.VM").getMethod("isBooted").invoke(null)
            + " " + Object.class.getModule().isExported("jdk.internal.misc", JarMain.class.getModule()));
        try { java.lang.invoke.MethodHandles.privateLookupIn(ArrayList.class, java.lang.invoke.MethodHandles.lookup()); System.out.println("java.util: opened"); }
        catch (IllegalAccessException e) { System.out.println("java.util: " + e.getMessage().replaceAll("@[0-9a-f]+", "@N")); }
    }
}
