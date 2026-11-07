package om;

import java.io.*;
import java.net.URL;

// eight resolved modules each holding META-INF/x (its own name): the loader
// searches them in its own order (BuiltinClassLoader.nameToModule's), so the
// first match and the list's order are HotSpot's
public class Main {
    static String read(URL u) throws IOException { try (InputStream in = u.openStream()) { return new String(in.readAllBytes()).trim(); } }
    public static void main(String[] a) throws IOException {
        ClassLoader l = ClassLoader.getSystemClassLoader();
        System.out.println("getResource " + read(l.getResource("META-INF/x")));
        StringBuilder b = new StringBuilder();
        for (URL u : java.util.Collections.list(l.getResources("META-INF/x"))) b.append(read(u)).append(' ');
        System.out.println("getResources " + b.toString().trim());
    }
}
