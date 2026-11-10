import champaca.lib.Greeter;
import champaca.lib.Shelf;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.ServiceLoader;

// Classes from a jar on the classpath (target/lib.jar, built by jolt test from
// test/jar) and from JDK modules other than java.base.
public class JarCheck {
  public static void main(String[] args) throws Exception {
    System.out.println(Arrays.toString(Shelf.fill(String.class, 3, "x")));
    System.out.println(Shelf.fill(Integer.class, 2, 7).getClass().getSimpleName());
    System.out.println(Shelf.sum(new int[] {1, 2, 3, 4}));
    int[][] g = Shelf.grid(2, 3);
    System.out.println(g.length + "x" + g[0].length + " " + g[1][2]);
    System.out.println(Shelf.bumpThroughUnsafe());
    System.out.println(new java.sql.Date(0L).getClass().getName());
    System.out.println(java.util.logging.Level.WARNING.getName());
    // the zone database ($JAVA_HOME/lib/tzdb.dat): FileInputStream, ByteArray reads, CRC32
    System.out.println(java.util.TimeZone.getTimeZone("Asia/Tokyo").getRawOffset());
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update("champaca".getBytes());
    System.out.println(crc.getValue());
    // resources in the jar: a service registration, a URL, a stream
    for (Greeter greeter : ServiceLoader.load(Greeter.class)) System.out.println(greeter.greet("jar"));
    java.net.URL url = Shelf.class.getResource("shelf.properties");
    System.out.println(url.getProtocol() + " " + url.getPath().endsWith("lib.jar!/champaca/lib/shelf.properties"));
    Properties props = new Properties();
    try (java.io.InputStream in = url.openStream()) { props.load(in); }
    System.out.println(props.getProperty("shelf.color"));
    try (java.io.InputStream in = Shelf.class.getResourceAsStream("/champaca/lib/shelf.properties")) {
      Properties again = new Properties();
      again.load(in);
      System.out.println(again.getProperty("shelf.rows"));
    }
    System.out.println(Collections.list(ClassLoader.getSystemResources("META-INF/services/champaca.lib.Greeter")).size()
        + " " + (Shelf.class.getResource("missing.txt") == null));
  }
}
