import java.lang.reflect.*;
import java.util.*;

// A Method or Constructor object resolves once and keeps what it resolved;
// each row runs the same objects over several passes and receivers, so the
// first call's answer can't stick: an override is chosen per receiver, every
// kind of method dispatches as on the JVM, and primitives box and unbox both
// ways.
public class ReflectCacheCheck {
    public static class Base {
        public String who() { return "Base"; }
        String pkg() { return "Base.pkg"; }
        private String priv() { return "Base.priv"; }
        public static int twice(int x) { return 2 * x; }
        public static String callPriv(Base b) throws Exception {
            Method m = Base.class.getDeclaredMethod("priv"); return (String) m.invoke(b);
        }
    }
    public static class Sub extends Base {
        public String who() { return "Sub"; }
        String pkg() { return "Sub.pkg"; }
        private String priv() { return "Sub.priv"; }
    }
    public interface Named { String name(); }
    public static class N1 implements Named { public String name() { return "N1"; } }
    public static class N2 implements Named { public String name() { return "N2"; } }
    public static class Prims {
        public boolean z(boolean v) { return !v; }   public byte b(byte v) { return (byte) (v + 1); }
        public char c(char v) { return (char) (v + 1); } public short s(short v) { return (short) (v + 1); }
        public int i(int v) { return v + 1; }         public long j(long v) { return v + 1; }
        public float f(float v) { return v + 1; }     public double d(double v) { return v + 1; }
    }
    public static class Thrower { public void go() { throw new IllegalStateException("from go"); } }
    public static class Made { final int x; final String s; public Made() { this(0, "none"); } public Made(int x, String s) { this.x = x; this.s = s; } public String toString() { return x + "/" + s; } }

    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String err(Callable c) { try { return String.valueOf(c.call()); } catch (Throwable t) { return t.getClass().getName() + ": " + t.getMessage() + (t.getCause() != null ? " <- " + t.getCause() : ""); } }
    interface Callable { Object call() throws Throwable; }

    public static void main(String[] args) throws Exception {
        Method who = Base.class.getMethod("who");
        Method pkg = Base.class.getDeclaredMethod("pkg");
        Method twice = Base.class.getMethod("twice", int.class);
        Method name = Named.class.getMethod("name");
        Method go = Thrower.class.getMethod("go");
        Constructor<Made> m0 = Made.class.getConstructor();
        Constructor<Made> m2 = Made.class.getConstructor(int.class, String.class);
        Object[] receivers = { new Base(), new Sub(), new Base(), new Sub() };
        Prims p = new Prims();
        for (int pass = 0; pass < 3; pass++) {
            String t = " (pass " + pass + ")";
            StringBuilder sb = new StringBuilder();
            for (Object r : receivers) sb.append(who.invoke(r)).append(' ').append(pkg.invoke(r)).append(' ');
            row("override per receiver" + t, sb.toString().trim());
            row("private, own class" + t, Base.callPriv(new Sub()) + " " + Base.callPriv(new Base()));
            row("static" + t, twice.invoke(null, 21) + " " + twice.invoke(null, (short) 4));
            row("interface" + t, name.invoke(new N1()) + " " + name.invoke(new N2()) + " " + name.invoke(new N1()));
            row("primitives" + t, Prims.class.getMethod("z", boolean.class).invoke(p, true) + " " + Prims.class.getMethod("b", byte.class).invoke(p, (byte) 1)
                + " " + Prims.class.getMethod("c", char.class).invoke(p, 'a') + " " + Prims.class.getMethod("s", short.class).invoke(p, (short) 2)
                + " " + Prims.class.getMethod("i", int.class).invoke(p, 3) + " " + Prims.class.getMethod("j", long.class).invoke(p, 4)
                + " " + Prims.class.getMethod("f", float.class).invoke(p, 5) + " " + Prims.class.getMethod("d", double.class).invoke(p, 6.5));
            row("constructors" + t, m0.newInstance() + " " + m2.newInstance(pass, "made") + " " + m2.newInstance((byte) 7, "widened"));
            row("wrong receiver" + t, err(() -> who.invoke("a string")));
            row("null receiver" + t, err(() -> who.invoke(null)));
            row("thrown inside" + t, err(() -> go.invoke(new Thrower())));
            row("wrong argument" + t, err(() -> m2.newInstance("x", "y")));
        }
    }
}
