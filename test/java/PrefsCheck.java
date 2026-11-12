import java.io.*;
import java.util.*;
import java.util.prefs.*;

// java.util.prefs through the platform's own store, as on the JVM: macOS's
// CFPreferences (MacOSXPreferences), Linux's files under ~/.java
// (FileSystemPreferences). A node of its own, three levels deep so macOS
// gives it a file of its own too, written, flushed, read back, exported and
// removed. Keys and children are printed sorted, since the stores keep
// their own order.
public class PrefsCheck {
    static List<String> sorted(String[] a) { List<String> l = new ArrayList<>(Arrays.asList(a)); Collections.sort(l); return l; }
    public static void main(String[] args) throws Exception {
        Preferences root = Preferences.userRoot();
        String base = "champaca-prefscheck";
        if (root.nodeExists(base)) { root.node(base).removeNode(); root.flush(); }
        Preferences top = root.node(base);
        Preferences deep = top.node("a/b/c");
        top.put("top", "t");
        deep.put("s", "é and 日");
        deep.putInt("i", -7);
        deep.putBoolean("z", true);
        deep.putDouble("d", 2.5);
        deep.putByteArray("bytes", new byte[]{1, 2, (byte) 250});
        top.node("other");
        root.flush();
        System.out.println("keys: " + sorted(deep.keys()));
        System.out.println("values: " + deep.get("s", "?") + " " + deep.getInt("i", 0) + " " + deep.getBoolean("z", false) + " "
            + deep.getDouble("d", 0) + " " + Arrays.toString(deep.getByteArray("bytes", null)) + " " + deep.get("missing", "default"));
        System.out.println("children: " + sorted(top.childrenNames()) + " " + sorted(top.node("a/b").childrenNames()));
        System.out.println("paths: " + deep.absolutePath() + " " + deep.name() + " " + deep.isUserNode());
        deep.remove("z");
        System.out.println("after remove: " + sorted(deep.keys()) + " " + deep.getBoolean("z", false));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        top.node("other").put("x", "y");
        top.node("other").exportNode(out);
        String xml = out.toString("UTF-8");
        System.out.println("export: " + xml.contains("<entry key=\"x\" value=\"y\"/>") + " " + xml.contains("<!DOCTYPE preferences"));
        top.node("other").clear();
        Preferences.importPreferences(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        System.out.println("import: " + top.node("other").get("x", "?"));
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        top.addPreferenceChangeListener(e -> events.add(e.getKey() + "=" + e.getNewValue()));
        top.put("watched", "1");
        top.flush();
        for (int i = 0; i < 200 && events.isEmpty(); i++) Thread.sleep(10);
        System.out.println("event: " + events);
        top.removeNode();
        root.flush();
        System.out.println("removed: " + root.nodeExists(base));
        try { deep.get("s", "?"); } catch (IllegalStateException e) { System.out.println("removed node: " + e.getMessage()); }
    }
}
