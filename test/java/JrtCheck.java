import java.net.URI;
import java.nio.file.*;
import java.util.*;

// The jrt: file system: the default one, and FileSystems.newFileSystem(jrt:/,
// {java.home: this JDK}), which the JDK serves by loading the home's
// lib/jrt-fs.jar through a loader of its own (Kotlin's compiler opens its JDK
// that way); champaca answers that one from the image it runs on.
public class JrtCheck {
    static byte[] object(FileSystem fs) throws Exception {
        return Files.readAllBytes(fs.getPath("/modules/java.base/java/lang/Object.class"));
    }
    static void rows(String label, FileSystem fs) throws Exception {
        byte[] b = object(fs);
        System.out.println(label + ": magic " + Integer.toHexString(((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff)));
        try (var s = Files.list(fs.getPath("/modules"))) {
            System.out.println(label + ": /modules has java.base " + s.anyMatch(x -> x.toString().equals("/modules/java.base")));
        }
        try (var s = Files.list(fs.getPath("/packages/java.lang"))) {
            System.out.println(label + ": java.lang in " + s.map(Path::toString).sorted().toList());
        }
        System.out.println(label + ": provider " + fs.provider().getClass().getName() + ", scheme " + fs.provider().getScheme());
        System.out.println(label + ": is directory " + Files.isDirectory(fs.getPath("/modules/java.base/java/util")));
    }
    public static void main(String[] args) throws Exception {
        FileSystem def = FileSystems.getFileSystem(URI.create("jrt:/"));
        rows("default", def);
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", System.getProperty("java.home")))) {
            rows("java.home", fs);
            System.out.println("same Object.class bytes: " + Arrays.equals(object(def), object(fs)));
        }
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", "/no/such/jdk"))) {
            System.out.println("no jdk: opened");
        } catch (Exception e) {
            System.out.println("no jdk: " + e);
        }
    }
}
