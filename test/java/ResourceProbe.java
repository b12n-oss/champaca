import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// ResourceCheck's class for a loader of its own: ResourceCheck deletes this
// class file and puts its bytes in a jar, so only that loader defines it
public class ResourceProbe {
    public String own() throws IOException { return text(ResourceProbe.class.getResourceAsStream("probe.txt")); }
    public String relative() { return found(ResourceProbe.class.getResource("probe.txt")); }
    public String absolute() { return found(ResourceProbe.class.getResource("/d/a.txt")); }
    static String text(InputStream in) throws IOException {
        if (in == null) return "null";
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    static String found(URL u) { return u == null ? "null" : u.getProtocol(); }
}
