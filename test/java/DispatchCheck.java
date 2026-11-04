import champaca.dispatch.a.Back;
import champaca.dispatch.a.Base;
import champaca.dispatch.a.Weak;
import champaca.dispatch.b.AfterBridge;
import champaca.dispatch.b.AfterMid;
import champaca.dispatch.b.Foreign;
import champaca.dispatch.b.Mid;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

// Which method a call selects (JVMS 5.4.5), through bytecode, reflection, a
// method handle and a method reference: a package-private method is
// overridden only from its run-time package (package and loader), or
// transitively through a method that overrides it; a private method is
// called as itself and overrides nothing. Fixtures in test/jar's
// champaca/dispatch. Expected output recorded from HotSpot.
public class DispatchCheck {
    static int n;
    static void row(String name, Callable c) {
        String r;
        try { r = String.valueOf(c.call()); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(++n + " " + name + ": " + r);
    }
    interface Callable { Object call() throws Throwable; }

    static String all(Base b) throws Throwable {
        Method m = Base.class.getDeclaredMethod("m");
        m.setAccessible(true);
        return "call=" + Base.call(b) + " reflect=" + m.invoke(b)
            + " handle=" + Base.viaHandle(b) + " reference=" + Base.viaReference(b);
    }

    // A class of test/jar defined from its bytes by this loader: another
    // run-time package for the same package name. With privatize, the named
    // method's access becomes private, which javac won't write.
    static class Fixtures extends ClassLoader {
        Fixtures() { super(DispatchCheck.class.getClassLoader()); }
        Class<?> define(String name, String privatize) throws IOException {
            byte[] bytes;
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                bytes = in.readAllBytes();
            }
            if (privatize != null) patch(bytes, privatize);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
    // the class file walk is upstream OpenLDK's ReflectionDispatchProbe's
    static void patch(byte[] bytes, String method) throws IOException {
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        DataInputStream in = new DataInputStream(raw);
        in.skipNBytes(8);
        String[] utf = new String[in.readUnsignedShort()];
        for (int i = 1; i < utf.length; i++) {
            switch (in.readUnsignedByte()) {
                case 1 -> utf[i] = in.readUTF();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new AssertionError("constant pool tag");
            }
        }
        in.skipNBytes(6);
        in.skipNBytes(2L * in.readUnsignedShort());
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) { in.skipNBytes(6); attributes(in); }
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            int offset = bytes.length - raw.available();
            int flags = in.readUnsignedShort();
            String name = utf[in.readUnsignedShort()];
            in.readUnsignedShort();
            if (name.equals(method)) {
                int f = (flags & ~7) | 2;
                bytes[offset] = (byte) (f >> 8);
                bytes[offset + 1] = (byte) f;
                return;
            }
            attributes(in);
        }
        throw new AssertionError("no method " + method);
    }
    static void attributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }

    // private serialization hooks in a class and its subclass: each runs for
    // its own class's part of the object
    static final List<String> log = new ArrayList<>();
    static class SerA implements Serializable {
        int a = 1;
        private void writeObject(ObjectOutputStream o) throws IOException { log.add("A.write"); o.defaultWriteObject(); }
        private void readObject(ObjectInputStream i) throws IOException, ClassNotFoundException { log.add("A.read"); i.defaultReadObject(); }
    }
    static class SerB extends SerA {
        int b = 2;
        private void writeObject(ObjectOutputStream o) throws IOException { log.add("B.write"); o.defaultWriteObject(); }
        private void readObject(ObjectInputStream i) throws IOException, ClassNotFoundException { log.add("B.read"); i.defaultReadObject(); }
    }

    public static void main(String[] args) throws Throwable {
        row("Base", () -> all(new Base()));
        row("same package", () -> all(new Base.Same()));
        row("same package, public", () -> all(new Base.Bridge()));
        row("another package", () -> all(new Foreign()));
        row("another package, public, below Base", () -> all(new Mid()));
        row("Base's package below Mid", () -> all(new Back()));
        row("another package below Back", () -> all(new AfterMid()));
        row("another package below Bridge", () -> all(new AfterBridge()));
        Fixtures fx = new Fixtures();
        row("same package name, another loader", () -> all((Base) fx.define("champaca.dispatch.a.Isolated", null).getConstructor().newInstance()));
        row("private, bytecode", () -> Base.callPrivate(new Foreign()));
        row("private, reflection", () -> {
            Method h = Base.class.getDeclaredMethod("h");
            h.setAccessible(true);
            return h.invoke(new Foreign());
        });
        row("private, findVirtual", () -> Base.viaPrivateHandle(new Foreign()));
        row("private, unreflect", () -> {
            Method h = Base.class.getDeclaredMethod("h");
            h.setAccessible(true);
            return MethodHandles.lookup().unreflect(h).invoke(new Foreign());
        });
        row("public h, another package", () -> Foreign.class.getMethod("h").invoke(new Foreign()));
        row("private, same package, reflection", () -> {
            Method h = Base.class.getDeclaredMethod("h");
            h.setAccessible(true);
            return h.invoke(new Base.SameH()) + " " + Base.viaPrivateHandle(new Base.SameH());
        });
        row("an override made private", () -> {
            Weak x = (Weak) fx.define("champaca.dispatch.a.WeakSub", "w").getConstructor().newInstance();
            Method w = Weak.class.getMethod("w");
            return "call=" + Weak.call(x) + " reflect=" + w.invoke(x) + " handle="
                + MethodHandles.publicLookup().unreflect(w).invoke(x);
        });
        row("a private method below a public anchor", () -> all((Base) fx.define("champaca.dispatch.b.PrivateAfterBridge", "m").getConstructor().newInstance()));
        row("serialization hooks", () -> {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ObjectOutputStream o = new ObjectOutputStream(bo)) { o.writeObject(new SerB()); }
            SerB back;
            try (ObjectInputStream i = new ObjectInputStream(new ByteArrayInputStream(bo.toByteArray()))) { back = (SerB) i.readObject(); }
            return log + " " + back.a + " " + back.b;
        });
    }
}
