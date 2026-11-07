package cp;

import java.io.*;

// a class path class asking for the module path's resources, by loader and by
// class: none while com.greet isn't resolved; with it resolved (--add-modules),
// its opened package and resources in no package, never its closed package
public class Res {
    static String read(InputStream in) throws IOException { return in == null ? "null" : new String(in.readAllBytes()).trim(); }
    public static void main(String[] a) throws IOException {
        ClassLoader l = ClassLoader.getSystemClassLoader();
        for (String n : new String[] {"com/greet/msg.txt", "com/greet/open/o.txt", "top.txt", "META-INF/greet.txt", "com/greet/Greeter.class"}) {
            System.out.println(n + ": stream " + (n.endsWith(".class") ? (l.getResourceAsStream(n) != null) : read(l.getResourceAsStream(n)))
                + " | url " + (l.getResource(n) != null)
                + " | all " + java.util.Collections.list(l.getResources(n)).size()
                + " | system " + (ClassLoader.getSystemResource(n) != null)
                + " | systems " + java.util.Collections.list(ClassLoader.getSystemResources(n)).size()
                + " | class " + (Res.class.getResource("/" + n) != null));
        }
    }
}
