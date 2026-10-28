import java.nio.charset.Charset;

// System properties from the launcher's -D options (test/java/PropsCheck.options):
// a property the VM doesn't own takes the option's value, the last of a key
// wins, -Dkey is an empty value, and the JDK reads them at startup
// (file.encoding is the default charset's name). A property the VM owns,
// sun.java.command here, keeps the VM's value. -Dchampaca.test.empty is the
// last option, so the empty value ends the options champaca passes along.
public class PropsCheck {
    static void row(String label, Object v) { System.out.println(label + ": " + v); }

    public static void main(String[] args) {
        row("plain", System.getProperty("champaca.test.plain"));
        row("empty", "[" + System.getProperty("champaca.test.empty") + "]");
        row("value with =", System.getProperty("champaca.test.eq"));
        row("value with spaces", System.getProperty("champaca.test.spaces"));
        row("last of a key", System.getProperty("champaca.test.twice"));
        row("absent", System.getProperty("champaca.test.absent"));
        row("Boolean.getBoolean", Boolean.getBoolean("champaca.test.flag"));
        row("Integer.getInteger", Integer.getInteger("champaca.test.n"));
        row("user.home", System.getProperty("user.home"));
        row("java.library.path", System.getProperty("java.library.path"));
        row("file.encoding", System.getProperty("file.encoding"));
        row("default charset", Charset.defaultCharset());
        row("sun.java.command", System.getProperty("sun.java.command"));
        row("in getProperties", System.getProperties().containsKey("champaca.test.plain"));
        System.setProperty("champaca.test.plain", "changed");
        row("set later", System.getProperty("champaca.test.plain"));
    }
}
