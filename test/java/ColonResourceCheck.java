import java.io.InputStream;
import java.net.URL;
import java.util.Collections;

// Class path resources whose names hold a colon, as the JDK's URLClassPath
// answers: a name that starts like a URL scheme with no protocol handler
// (cc-scheme:), or jar: without !/, is never found, in a directory or a jar,
// through any of the loader's or a class's lookups; a colon after a / or after
// a leading digit is found. The files come
// from test/gen/ColonResourceGen.java, written into target/classes and
// target/lib2.jar by bb test.
public class ColonResourceCheck {
    public static void main(String[] args) throws Exception {
        ClassLoader l = ColonResourceCheck.class.getClassLoader();
        String[] names = {"cc-scheme:dir.txt", "cc-scheme:jar.txt", "CC-Scheme+1.x:dir.txt", "CC-Scheme+1.x:jar.txt",
                          "jar:cc-dir.txt", "jar:cc-jar.txt", "9cc:dir.txt", "9cc:jar.txt", "cc-dir/cc:x.txt", "cc-jar/cc:x.txt"};
        for (String n : names) {
            URL u = l.getResource(n);
            String text;
            try (InputStream in = l.getResourceAsStream(n)) { text = in == null ? "null" : new String(in.readAllBytes()).trim(); }
            System.out.println(n + ": url " + tail(u) + ", stream " + text
                + ", all " + Collections.list(l.getResources(n)).size()
                + ", class " + (ColonResourceCheck.class.getResource("/" + n) != null)
                + ", system " + (ClassLoader.getSystemResource(n) != null));
        }
    }

    // the part after the class path entry, so the row is machine-independent
    static String tail(URL u) {
        if (u == null) return "null";
        String s = u.toString();
        int i = s.indexOf("!/");
        return i >= 0 ? "jar " + s.substring(i + 2) : "dir " + s.substring(s.indexOf("/classes/") + 9);
    }
}
