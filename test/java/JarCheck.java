import champaca.lib.Shelf;
import java.util.Arrays;

// Classes from a jar on the classpath (target/lib.jar, built by bb test from
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
  }
}
