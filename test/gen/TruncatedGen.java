import java.nio.file.*;

// Writes Truncated.class: the first half of Hello.class, a class file that
// exists but can't be loaded. The JVM throws ClassFormatError for it and
// champaca NoClassDefFoundError, both LinkageErrors rather than
// ClassNotFoundException (ModuleCheck's "exists but can't load" row).
// usage: java test/gen/TruncatedGen.java <dir with Hello.class>
public class TruncatedGen {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        byte[] hello = Files.readAllBytes(dir.resolve("Hello.class"));
        Files.write(dir.resolve("Truncated.class"), java.util.Arrays.copyOf(hello, hello.length / 2));
    }
}
