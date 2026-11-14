import java.lang.invoke.MethodHandles;
import java.util.*;

// Class.forName keeps what it worked out for a String it was given; each row
// asks with the same String objects over three passes, so a kept answer must
// still depend on the loader asked, never be a failure, and come back for a
// class defined after a lookup that missed.
public class ForNameCacheCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String look(String n, boolean init, ClassLoader l) {
        try { Class<?> c = Class.forName(n, init, l); return c.getName() + "@" + (c.getClassLoader() == null ? "boot" : c.getClassLoader().getName()); }
        catch (Throwable t) { return t.getClass().getSimpleName() + ": " + t.getMessage(); }
    }

    public static void main(String[] args) throws Exception {
        ClassLoader boot = null, platform = ClassLoader.getPlatformClassLoader(), app = ClassLoader.getSystemClassLoader();
        String sql = "java.sql.Date", list = "java.util.ArrayList", own = "ForNameCacheCheck", arr = "[Ljava.sql.Date;", prim = "[[I";
        String[] bad = { "", "java/util/ArrayList", "[", "[Ljava.lang.String", "int", "no.such.Thing" };
        for (int pass = 0; pass < 3; pass++) {
            String t = " (pass " + pass + ")";
            row("java.sql.Date by boot, platform, app" + t, look(sql, false, boot) + " | " + look(sql, false, platform) + " | " + look(sql, false, app));
            row("java.sql.Date by app, boot" + t, look(sql, false, app) + " | " + look(sql, false, boot));
            row("ArrayList by each" + t, look(list, true, boot) + " | " + look(list, true, platform) + " | " + look(list, true, app));
            row("own class by boot, app" + t, look(own, false, boot) + " | " + look(own, false, app));
            row("arrays" + t, look(arr, false, boot) + " | " + look(arr, false, platform) + " | " + look(prim, false, boot));
            StringBuilder sb = new StringBuilder();
            for (String b : bad) sb.append(look(b, false, app)).append(" | ");
            row("bad names" + t, sb);
        }
        // missed, then defined: the next lookup finds it
        String later = "ForNameCacheCheck$Later";
        row("before it is defined", look(later, false, app).startsWith("ClassNotFoundException"));
        row("before it is defined, again", look(later, false, app).startsWith("ClassNotFoundException"));
        byte[] b;
        try (var in = ForNameCacheCheck.class.getResourceAsStream("ForNameCacheCheck$Model.class")) { b = in.readAllBytes(); }
        byte[] from = "ForNameCacheCheck$Model".getBytes(), to = "ForNameCacheCheck$Later".getBytes();
        outer:
        for (int i = 0; i + from.length <= b.length; i++) {
            for (int j = 0; j < from.length; j++) if (b[i + j] != from[j]) continue outer;
            System.arraycopy(to, 0, b, i, to.length);
        }
        MethodHandles.lookup().defineClass(b);
        row("after it is defined", look(later, false, app));
        row("after it is defined, again", look(later, false, app));
    }
    static class Model {}
}
