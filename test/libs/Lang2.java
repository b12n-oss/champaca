import java.io.*;
import java.util.*;
import org.apache.commons.lang.SerializationUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang.time.DurationFormatUtils;
// commons-lang 2.4, built for Java 1.2 (class file version 46): its
// SerializationUtils.serialize and deserialize keep their finally blocks as
// jsr/ret subroutines, the way javac 1.4 and earlier compiled them
public class Lang2 {
  public static void main(String[] a) throws Exception {
    List<Object> l = new ArrayList<>(List.of("a", 1, List.of(2.5, 'c')));
    System.out.println(SerializationUtils.clone((Serializable) l));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SerializationUtils.serialize(new TreeMap<>(Map.of("k", "v", "n", 3)), out);
    System.out.println(SerializationUtils.deserialize(new ByteArrayInputStream(out.toByteArray())));
    try {
      SerializationUtils.deserialize(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    } catch (Exception e) {
      System.out.println(e.getClass().getName() + ": " + e.getMessage());
    }
    System.out.println(StringUtils.capitalize("champaca"));
    System.out.println(WordUtils.wrap("a sentence long enough to wrap twice here", 12));
    System.out.println(DurationFormatUtils.formatDurationHMS(3723004L));
  }
}
