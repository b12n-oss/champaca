import com.google.common.hash.*;
import com.google.common.collect.*;
import com.google.common.base.*;
import java.nio.charset.StandardCharsets;

public class Gua {
    public static void main(String[] args) {
        String s = "The quick brown fox jumps over the lazy dog";
        System.out.println("crc32c " + Hashing.crc32c().hashString(s, StandardCharsets.UTF_8));
        System.out.println("crc32 " + Hashing.crc32().hashString(s, StandardCharsets.UTF_8));
        System.out.println("adler32 " + Hashing.adler32().hashString(s, StandardCharsets.UTF_8));
        System.out.println("murmur3 " + Hashing.murmur3_128().hashString(s, StandardCharsets.UTF_8));
        System.out.println("sha256 " + Hashing.sha256().hashString(s, StandardCharsets.UTF_8));
        System.out.println("sha512 " + Hashing.sha512().hashString(s, StandardCharsets.UTF_8));
        System.out.println("md5 " + Hashing.md5().hashString(s, StandardCharsets.UTF_8));
        System.out.println("farm " + Hashing.farmHashFingerprint64().hashBytes(s.getBytes(StandardCharsets.UTF_8)));
        Hasher h = Hashing.crc32c().newHasher();
        for (int i = 0; i < 1000; i++) h.putInt(i).putLong(i * 31L);
        System.out.println("crc32c stream " + h.hash());
        System.out.println("bloom " + BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 100).mightContain("x"));
        ImmutableMultimap<String, Integer> mm = ImmutableListMultimap.of("a", 1, "a", 2, "b", 3);
        System.out.println("multimap " + mm + " " + Joiner.on('|').join(Splitter.on(',').trimResults().split(" x , y ,z")));
    }
}
