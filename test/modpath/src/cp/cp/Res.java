package cp;

import java.io.*;

// a class path class asking for the module path's resources, by loader and by
// class: none while com.greet isn't resolved; with it resolved (--add-modules),
// its opened package and resources in no package, never its closed package,
// and a resource in one of its packages only from the module, never the class path
public class Res {
    static String read(InputStream in) throws IOException { return in == null ? "null" : new String(in.readAllBytes()).trim(); }
    public static void main(String[] a) throws IOException {
        ClassLoader l = ClassLoader.getSystemClassLoader();
        for (String n : new String[] {"com/greet/msg.txt", "com/greet/open/o.txt", "com/greet/open/extra.txt", "top.txt", "META-INF/greet.txt", "com/greet/Greeter.class", "com/greet/", "com/greet/open"}) {
            boolean bin = n.endsWith(".class") || n.endsWith("/") || n.endsWith("open");
            System.out.println(n + ": stream " + (bin ? (l.getResourceAsStream(n) != null) : read(l.getResourceAsStream(n)))
                + " | url " + (l.getResource(n) != null)
                + " | all " + java.util.Collections.list(l.getResources(n)).size()
                + " | system " + (ClassLoader.getSystemResource(n) != null)
                + " | systems " + java.util.Collections.list(ClassLoader.getSystemResources(n)).size()
                + " | class " + (Res.class.getResource("/" + n) != null));
        }
        // a class of a module defined to the application loader: the platform
        // loader finds it there (built-in loaders delegate by package), the
        // boot loader doesn't
        for (ClassLoader ld : new ClassLoader[] {ClassLoader.getPlatformClassLoader(), null}) {
            try { System.out.println((ld == null ? "boot" : "platform") + " forName " + Class.forName("com.greet.Greeter", false, ld).getModule().getName()); }
            catch (ClassNotFoundException e) { System.out.println((ld == null ? "boot" : "platform") + " forName " + e.getClass().getSimpleName()); }
        }
    }
}
