import java.lang.reflect.*;

// What Field.get, Field.set and their typed forms (getInt, setLong, ...) do
// with a value or a receiver that doesn't fit: a primitive widens, never
// narrows; anything else is IllegalArgumentException with the JDK's
// "Can not set ..." or "Attempt to get ..." message.
public class FieldArgCheck {
    public int i = 1;
    public long j = 2;
    public short s = 3;
    public byte b = 4;
    public char c = 'c';
    public boolean z = true;
    public float f = 1.5f;
    public double d = 2.5;
    public String str = "str";
    public Number num = 7;
    public int[] arr = {1};
    public Object obj = "obj";
    public static int si = 9;
    public static String sstr = "sstr";
    public final int fin = 12;

    interface Op { void run() throws Exception; }
    static void row(String name, Op op) {
        String r;
        try { op.run(); r = "ok"; }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(name + ": " + r);
    }
    interface Get { Object get() throws Exception; }
    static void val(String name, Get g) {
        String r;
        try { r = String.valueOf(g.get()); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(name + ": " + r);
    }
    static Field f(String n) throws Exception { return FieldArgCheck.class.getField(n); }

    public static void main(String[] args) throws Exception {
        FieldArgCheck o = new FieldArgCheck();
        // set: unboxed and widened
        row("set int Short", () -> f("i").set(o, (short) 5));
        row("set int Character", () -> f("i").set(o, 'a'));
        row("set long Integer", () -> f("j").set(o, 6));
        row("set double Float", () -> f("d").set(o, 0.5f));
        row("set float Long", () -> f("f").set(o, 3L));
        row("set Number Integer", () -> f("num").set(o, 8));
        row("set Object int[]", () -> f("obj").set(o, new int[0]));
        row("set String null", () -> f("str").set(o, null));
        System.out.println("after: " + o.i + " " + o.j + " " + o.d + " " + o.f + " " + o.num + " " + o.str);
        // set: refused
        row("set int Long", () -> f("i").set(o, 5L));
        row("set int null", () -> f("i").set(o, null));
        row("set int String", () -> f("i").set(o, "5"));
        row("set short Character", () -> f("s").set(o, 'x'));
        row("set char Byte", () -> f("c").set(o, (byte) 1));
        row("set boolean Integer", () -> f("z").set(o, 1));
        row("set String Integer", () -> f("str").set(o, 5));
        row("set Number String", () -> f("num").set(o, "5"));
        row("set int[] long[]", () -> f("arr").set(o, new long[0]));
        row("set static int Long", () -> f("si").set(null, 5L));
        row("set static String Integer", () -> f("sstr").set(null, 5));
        System.out.println("unchanged: " + o.i + " " + o.s + " " + o.c + " " + o.z + " " + o.str + " " + o.num + " " + si + " " + sstr);
        // typed setters
        row("setInt long", () -> f("j").setInt(o, 10));
        row("setChar int", () -> f("i").setChar(o, 'b'));
        row("setByte short", () -> f("s").setByte(o, (byte) 2));
        row("setFloat double", () -> f("d").setFloat(o, 0.25f));
        row("setInt double", () -> f("d").setInt(o, 7));
        row("setInt Object", () -> f("obj").setInt(o, 1));
        row("setLong int", () -> f("i").setLong(o, 10L));
        row("setShort char", () -> f("c").setShort(o, (short) 1));
        row("setBoolean int", () -> f("i").setBoolean(o, true));
        row("setDouble float", () -> f("f").setDouble(o, 1.0));
        row("setInt String", () -> f("str").setInt(o, 1));
        row("setInt boolean", () -> f("z").setInt(o, 1));
        row("setInt static int", () -> f("si").setInt(null, 11));
        System.out.println("after typed: " + o.j + " " + o.i + " " + o.s + " " + o.d + " " + o.str + " " + si);
        System.out.println("d / 3 after setInt: " + (o.d / 3));
        row("set double Integer", () -> f("d").set(o, 5));
        System.out.println("d / 3 after set: " + (o.d / 3));
        // typed getters
        val("getLong int", () -> f("i").getLong(o));
        val("getInt char", () -> f("c").getInt(o));
        val("getShort byte", () -> f("b").getShort(o));
        val("getDouble float", () -> f("f").getDouble(o));
        val("getDouble int", () -> f("i").getDouble(o));
        val("getDouble int / 3", () -> f("i").getDouble(o) / 3);
        val("getInt long", () -> f("j").getInt(o));
        val("getChar int", () -> f("i").getChar(o));
        val("getBoolean int", () -> f("i").getBoolean(o));
        val("getInt String", () -> f("str").getInt(o));
        val("getByte short", () -> f("s").getByte(o));
        val("getFloat double", () -> f("d").getFloat(o));
        // receivers
        val("get other receiver", () -> f("i").get("a string"));
        val("get null receiver", () -> f("i").get(null));
        row("set other receiver", () -> f("i").set("a string", 1));
        row("set null receiver", () -> f("i").set(null, 1));
        val("get static null receiver", () -> f("si").get(null));
        val("get static other receiver", () -> f("si").get("ignored"));
        // a final field made accessible: set works, and the messages say final
        Field fin = f("fin");
        fin.setAccessible(true);
        row("set final int String", () -> fin.set(o, "x"));
        row("setLong final int", () -> fin.setLong(o, 1L));
        val("get final other receiver", () -> fin.get("a string"));
        val("accessible get null receiver", () -> fin.get(null));
        row("accessible set null receiver", () -> fin.set(null, 1));
        row("set final int Integer", () -> fin.set(o, 13));
        val("final after set", () -> fin.get(o));
    }
}
