import java.lang.reflect.*;
import java.util.*;

// Field.get, Field.set and the typed forms check access as the JVM does
// when the field wasn't made accessible: against the calling class, with
// IllegalAccessException in the JDK's words, a protected field also by the
// receiver's class. A null receiver is a NullPointerException first, and an
// inaccessible field with a receiver of another class is the access error.
public class FieldAccessCheck {
    static class Nested { private int secret = 7; private static int ssecret = 8; }
    static class MyList extends AbstractList<Integer> {
        public Integer get(int i) { return i; }
        public int size() { return 0; }
        Object peek(Object target) throws Exception { return f(AbstractList.class, "modCount").get(target); }
        Object poke(Object target) throws Exception { f(AbstractList.class, "modCount").setInt(target, 3); return "set"; }
    }
    private int own = 5;

    interface Run { Object run() throws Throwable; }
    static void row(String name, Run r) {
        String out;
        try { out = String.valueOf(r.run()); }
        catch (Throwable t) { out = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(name + " = " + out);
    }
    static Field f(Class<?> c, String n) throws Exception { return c.getDeclaredField(n); }

    public static void main(String[] args) throws Exception {
        row("private instance get", () -> f(Integer.class, "value").get(5));
        row("private instance getInt", () -> f(Integer.class, "value").getInt(5));
        row("private instance set", () -> { f(Integer.class, "value").set(5, 6); return "set"; });
        row("private instance setInt", () -> { f(Integer.class, "value").setInt(5, 6); return "set"; });
        row("private final object field", () -> f(Thread.class, "holder").get(Thread.currentThread()));
        row("private static get", () -> f(Integer.class, "digits").get(null));
        row("package static get", () -> f(String.class, "COMPACT_STRINGS").get(null));
        row("package static getBoolean", () -> f(String.class, "COMPACT_STRINGS").getBoolean(null));
        row("private field of ArrayList", () -> f(ArrayList.class, "size").get(new ArrayList<>()));
        row("protected, not a subclass", () -> f(AbstractList.class, "modCount").get(new MyList()));
        row("protected from a subclass, its own kind", () -> new MyList().peek(new MyList()));
        row("protected from a subclass, another class", () -> new MyList().peek(new ArrayList<>()));
        row("protected setInt from a subclass, its own kind", () -> new MyList().poke(new MyList()));
        row("protected setInt from a subclass, another class", () -> new MyList().poke(new ArrayList<>()));
        row("nestmate private get", () -> f(Nested.class, "secret").get(new Nested()));
        row("nestmate private static", () -> f(Nested.class, "ssecret").getInt(null));
        row("own private get", () -> f(FieldAccessCheck.class, "own").get(new FieldAccessCheck()));
        row("null receiver, inaccessible", () -> f(Integer.class, "value").get(null));
        row("wrong receiver, inaccessible", () -> f(Integer.class, "value").get("str"));
        row("wrong receiver, inaccessible set", () -> { f(Integer.class, "value").set("str", 1); return "set"; });
        row("trySetAccessible on java.base", () -> f(Integer.class, "value").trySetAccessible());
        row("public field of a public class", () -> f(Integer.class, "MAX_VALUE").get(null));
        row("accessible nested then get", () -> { Field x = f(Nested.class, "secret"); x.setAccessible(true); return x.get(new Nested()); });
        row("canAccess private", () -> f(Integer.class, "value").canAccess(5));
        row("canAccess public static", () -> f(Integer.class, "MAX_VALUE").canAccess(null));
    }
}
