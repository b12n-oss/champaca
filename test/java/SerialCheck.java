import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.*;
import java.util.*;

// Object serialization: ObjectOutputStream and ObjectInputStream round trips,
// the stream bytes themselves (class descriptors, computed serialVersionUIDs,
// field order), the hooks a class can define, and the class loader a stream
// resolves names through.
public class SerialCheck {
    static byte[] write(Object x) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(b)) { o.writeObject(x); }
        return b.toByteArray();
    }
    static Object read(byte[] bytes) throws Exception {
        try (ObjectInputStream i = new ObjectInputStream(new ByteArrayInputStream(bytes))) { return i.readObject(); }
    }
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    static long suid(Class<?> c) { return ObjectStreamClass.lookup(c).getSerialVersionUID(); }
    static void row(String name, Object v) { System.out.println(name + ": " + v); }

    // a plain serializable class: its serialVersionUID is computed from its shape
    static class Pt implements Serializable {
        int x; double d; String s; int[] arr; transient int t = 9; static int counter;
        Pt(int x, String s) { this.x = x; this.d = x / 2.0; this.s = s; this.arr = new int[]{x, x + 1}; }
        public String toString() { return "Pt(" + x + "," + d + "," + s + "," + Arrays.toString(arr) + ",t=" + t + ")"; }
    }
    // a non-serializable superclass: its no-arg constructor runs on
    // deserialization, the subclass's doesn't
    static class Base {
        int b = 7; String tag;
        Base() { tag = "base"; }
        Base(String t) { tag = t; }
    }
    static class Sub extends Base implements Serializable {
        int s;
        Sub(int s) { super("sub"); this.s = s; }
        public String toString() { return tag + " " + b + " " + s; }
    }
    // private writeObject/readObject around the default data
    static class Custom implements Serializable {
        int a; transient String derived;
        Custom(int a) { this.a = a; derived = "d" + a; }
        private void writeObject(ObjectOutputStream o) throws IOException { o.defaultWriteObject(); o.writeInt(a * 10); }
        private void readObject(ObjectInputStream i) throws IOException, ClassNotFoundException {
            i.defaultReadObject();
            derived = "r" + i.readInt();
        }
        public String toString() { return "Custom(" + a + "," + derived + ")"; }
    }
    // readResolve keeps a singleton; writeReplace substitutes another object
    static class Single implements Serializable {
        static final Single INSTANCE = new Single();
        private Object readResolve() { return INSTANCE; }
    }
    static class Replacer implements Serializable {
        int v;
        Replacer(int v) { this.v = v; }
        private Object writeReplace() { return "replaced:" + v; }
    }
    static class Ext implements Externalizable {
        int n; String s;
        public Ext() { n = -1; s = "ctor"; }
        Ext(int n, String s) { this.n = n; this.s = s; }
        public void writeExternal(ObjectOutput o) throws IOException { o.writeInt(n); o.writeUTF(s); }
        public void readExternal(ObjectInput i) throws IOException { n = i.readInt(); s = i.readUTF(); }
        public String toString() { return "Ext(" + n + "," + s + ")"; }
    }
    enum Color { RED, GREEN }
    record Pair(String k, int v) implements Serializable {}
    static class Declared implements Serializable {
        private static final long serialVersionUID = 42L;
        int q = 3;
    }
    static class Plain {}
    static class Holder implements Serializable { Object inner = new Plain(); }
    // two serializable levels: each level's fields in its own class descriptor
    static class Top implements Serializable { int a = 1; }
    static class Low extends Top { int b = 2; public String toString() { return a + "/" + b; } }
    // a superclass whose constructors are all private: no constructor serialization can use
    static class PrivBase { private PrivBase() {} }
    static class PrivSub extends PrivBase implements Serializable {}
    // looking a class up initializes it, before any instance exists
    static class LazyInit implements Serializable { static { System.out.println("LazyInit.<clinit>"); } }
    static abstract class Abs {}
    static class S1 extends Base {}
    static class S2 extends Base {}
    // nestmates: a nested class may reach the outer class's private members reflectively
    private static String secret() { return "secret"; }
    static class Nest {
        static String callSecret() throws Exception { return (String) SerialCheck.class.getDeclaredMethod("secret").invoke(null); }
    }
    interface Greeter { String hi(String n); }
    static class Handler implements InvocationHandler, Serializable {
        public Object invoke(Object p, Method m, Object[] a) { return "hi " + a[0]; }
    }

    // A serializable class that exists in one loader only, with a static
    // method that deserializes: the stream resolves names through the first
    // loader up the stack that isn't a built-in one.
    //   package gen; public class Remote implements Serializable {
    //     public int v;
    //     public static Object rt(byte[] b) throws Exception {
    //       return new ObjectInputStream(new ByteArrayInputStream(b)).readObject(); } }
    static byte[] remoteBytes() {
        ClassDesc self = ClassDesc.of("gen.Remote");
        ClassDesc ois = ClassDesc.of("java.io.ObjectInputStream");
        ClassDesc bais = ClassDesc.of("java.io.ByteArrayInputStream");
        ClassDesc bytes = ConstantDescs.CD_byte.arrayType();
        return ClassFile.of().build(self, cb -> cb
            .withFlags(ClassFile.ACC_PUBLIC)
            .withInterfaceSymbols(ClassDesc.of("java.io.Serializable"))
            .withField("v", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC)
            .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                c -> c.aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void).return_())
            .withMethodBody("rt", MethodTypeDesc.of(ConstantDescs.CD_Object, bytes), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                c -> c.new_(ois).dup().new_(bais).dup().aload(0)
                      .invokespecial(bais, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, bytes))
                      .invokespecial(ois, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("java.io.InputStream")))
                      .invokevirtual(ois, "readObject", MethodTypeDesc.of(ConstantDescs.CD_Object))
                      .areturn()));
    }
    static class Loader extends ClassLoader {
        final byte[] b;
        Loader(byte[] b) { super(SerialCheck.class.getClassLoader()); this.b = b; }
        protected Class<?> findClass(String n) throws ClassNotFoundException {
            if (n.equals("gen.Remote")) return defineClass(n, b, 0, b.length);
            throw new ClassNotFoundException(n);
        }
    }

    public static void main(String[] args) throws Exception {
        Pt p = new Pt(4, "four");
        byte[] bytes = write(p);
        row("pt suid", suid(Pt.class));
        row("pt bytes", hex(bytes));
        row("pt", read(bytes));
        row("list bytes", hex(write(new ArrayList<>(List.of("a", 1, 2.5)))));
        row("list", read(write(new ArrayList<>(List.of("a", 1, 2.5)))));
        row("map", read(write(new TreeMap<>(Map.of("k", List.of(1, 2), "z", Set.of())))));
        row("sub", read(write(new Sub(5))));
        row("custom", read(write(new Custom(3))));
        row("single suid", suid(Single.class));
        row("singleton", read(write(Single.INSTANCE)) == Single.INSTANCE);
        row("replaced", read(write(new Replacer(8))));
        row("ext", read(write(new Ext(2, "ok"))));
        row("enum", read(write(Color.GREEN)) == Color.GREEN);
        row("class", read(write(String.class)));
        row("record", read(write(new Pair("k", 1))));
        row("record suid", suid(Pair.class));
        row("declared suid", suid(Declared.class));
        row("arrays", Arrays.deepToString((Object[]) read(write(
            new Object[]{new int[]{1, 2}, new double[]{1.5}, new String[]{"s", null}, new Object[][]{{"n"}}}))));
        row("boxed", Arrays.toString((Object[]) read(write(new Object[]{1L, 2.5f, (short) 3, (byte) 4, 'c', true}))));
        Object[] two = (Object[]) read(write(new Object[]{p, p}));
        row("shared", two[0] == two[1]);
        row("chain", read(write(new Low())));
        List<?> jdk = (List<?>) read(write(List.of(new Date(1234), new java.math.BigDecimal("1.50"), java.time.LocalDate.of(2026, 10, 23),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), new HashMap<>(Map.of("a", 1)), java.time.Duration.ofSeconds(5),
            new StringBuilder("sb"), Optional.class, new java.util.concurrent.atomic.AtomicInteger(9))));
        row("jdk types", ((Date) jdk.get(0)).getTime() + " " + jdk.subList(1, jdk.size()));
        IllegalStateException ise = (IllegalStateException) read(write(new IllegalStateException("boom", new RuntimeException("why"))));
        row("throwable", ise + " / " + ise.getCause() + " / " + ise.getStackTrace()[0]);
        try { read(write(new PrivSub())); row("private super", "read"); } catch (InvalidClassException e) { row("private super", e.getMessage()); }
        row("lazy init", suid(LazyInit.class));
        // what Objenesis and Mockito do: allocate a class and run Object's constructor on it
        // (sun.reflect.ReflectionFactory, reached reflectively so javac doesn't warn)
        Class<?> rfc = Class.forName("sun.reflect.ReflectionFactory");
        Object rf = rfc.getMethod("getReflectionFactory").invoke(null);
        Method ncfs = rfc.getMethod("newConstructorForSerialization", Class.class, Constructor.class);
        row("objenesis", ((Constructor<?>) ncfs.invoke(rf, Sub.class, Object.class.getDeclaredConstructor())).newInstance());
        try { ncfs.invoke(rf, Sub.class, String.class.getDeclaredConstructor()); row("wrong super", "made"); }
        catch (InvocationTargetException e) { row("wrong super", e.getCause()); }
        // one Constructor object handed to two classes: each constructor keeps its own class
        Constructor<?> bc = Base.class.getDeclaredConstructor();
        Constructor<?> c1 = (Constructor<?>) ncfs.invoke(rf, S1.class, bc), c2 = (Constructor<?>) ncfs.invoke(rf, S2.class, bc);
        row("reused constructor", c1.newInstance().getClass().getSimpleName() + " " + c2.newInstance().getClass().getSimpleName()
            + " " + bc.newInstance().getClass().getSimpleName() + " " + ((Base) c1.newInstance()).tag);
        // classes the JVM refuses to allocate, at creation or at newInstance
        for (Class<?> decl : new Class<?>[]{Runnable.class, int[].class, Abs.class, Class.class}) {
            try {
                Constructor<?> c = (Constructor<?>) ncfs.invoke(rf, decl, Object.class.getDeclaredConstructor());
                try { c.newInstance(); row("allocate " + decl.getSimpleName(), "made"); }
                catch (InvocationTargetException e) { row("allocate " + decl.getSimpleName(), e.getCause()); }
            } catch (InvocationTargetException e) { row("allocate " + decl.getSimpleName(), e.getCause()); }
        }
        row("nestmates", Nest.callSecret() + " " + Pt.class.getNestHost().getSimpleName() + " " + Pt.class.isNestmateOf(Sub.class) + " "
            + new Object(){}.getClass().getNestHost().getSimpleName() + " " + Pt.class.isNestmateOf(String.class) + " " + int[].class.getNestHost().getSimpleName());
        try { write(new Plain()); row("plain", "written"); } catch (NotSerializableException e) { row("plain", e); }
        try { write(new Holder()); row("holder", "written"); } catch (NotSerializableException e) { row("holder", e); }
        // the stream's descriptor carries another serialVersionUID than the class
        byte[] d = write(new Declared());
        int at = hex(d).indexOf("000000000000002a") / 2;
        d[at + 7] = 0x2b;
        try { read(d); row("suid mismatch", "read"); } catch (InvalidClassException e) { row("suid mismatch", e.getMessage()); }
        Greeter g = (Greeter) Proxy.newProxyInstance(SerialCheck.class.getClassLoader(), new Class<?>[]{Greeter.class}, new Handler());
        Greeter g2 = (Greeter) read(write(g));
        row("proxy", g2.hi("x") + " " + Proxy.isProxyClass(g2.getClass()) + " " + (g2 != g));
        Class<?> rc = Class.forName("gen.Remote", true, new Loader(remoteBytes()));
        Object r = rc.getConstructor().newInstance();
        rc.getField("v").setInt(r, 77);
        byte[] rb = write(r);
        Object back = rc.getMethod("rt", byte[].class).invoke(null, rb);
        row("remote", back.getClass() == rc ? rc.getField("v").getInt(back) : "other class " + back.getClass());
        try { read(rb); row("remote from main", "read"); } catch (ClassNotFoundException e) { row("remote from main", e.getMessage()); }
    }
}
