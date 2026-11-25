// JDK code PruneUsed never reaches, so this run saves a generation of its
// own, and that save prunes the key directory.
import java.math.BigInteger;
import java.util.TreeMap;
import java.util.zip.CRC32;

public class PruneOther {
    public static void main(String[] args) {
        TreeMap<String, BigInteger> m = new TreeMap<>();
        m.put("b", BigInteger.TWO.pow(100));
        m.put("a", BigInteger.valueOf(7).modPow(BigInteger.valueOf(13), BigInteger.valueOf(1000)));
        CRC32 c = new CRC32();
        c.update("champaca".getBytes());
        System.out.println(m + " " + Long.toHexString(c.getValue()));
    }
}
