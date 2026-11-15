import java.net.URI;
import java.nio.file.*;
import java.util.Map;

// Writes resources whose names hold a colon: into the directory given first
// (a class path directory) and into the jar given second (a class path jar,
// added to what's there). The JDK's URLClassPath makes a resource's URL as
// new URL(entry's URL, name), so a name whose start reads as a URL scheme
// (a letter, then letters, digits, + . -, then a colon, before any /) with no
// protocol handler, or jar: without !/, is never found; a colon after a / or
// after a leading digit is an ordinary character. Each name says where it
// lives, so ColonResourceCheck's output shows which entry answered. Git keeps
// no file with a colon in its name, which a Windows checkout can't hold. Run
// with `java ColonResourceGen.java <dir> <jar>`.
public class ColonResourceGen {
    static final String[] NAMES = {"cc-scheme:%s.txt", "CC-Scheme+1.x:%s.txt", "jar:cc-%s.txt", "9cc:%s.txt", "cc-%s/cc:x.txt"};

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        for (String n : NAMES) {
            Path p = dir.resolve(String.format(n, "dir"));
            Files.createDirectories(p.getParent());
            Files.writeString(p, "from the directory\n");
        }
        URI jar = URI.create("jar:" + Path.of(args[1]).toAbsolutePath().toUri());
        try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of())) {
            for (String n : NAMES) {
                Path p = fs.getPath(String.format(n, "jar"));
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, "from the jar\n");
            }
        }
    }
}
