import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

// Method handles: the lookups (static, virtual, interface, constructor,
// special, fields, unreflect), invokeExact and invoke with boxing and
// widening, asType, bindTo, the MethodHandles combinators libraries reach
// for, invokeWithArguments, exceptions, and pattern switches (javac turns
// those into invokedynamic on SwitchBootstraps).
public class MethodHandleCheck {
    static int counter = 7;
    String label = "start";

    static String twice(String s) { return s + s; }
    static long widen(long x, double y) { return x + (long) y; }
    static void bump(int n) { counter += n; }
    static int sum(int... xs) { int t = 0; for (int x : xs) t += x; return t; }

    static class Base { String greet() { return "base"; } }
    static class Sub extends Base {
        String greet() { return "sub"; }
        String viaSpecial() throws Throwable {
            MethodHandle mh = MethodHandles.lookup().findSpecial(Base.class, "greet", MethodType.methodType(String.class), Sub.class);
            return (String) mh.invokeExact(this);
        }
    }

    record Point(int x, int y) {}
    sealed interface Shape permits Circle, Square {}
    record Circle(double r) implements Shape {}
    record Square(double side) implements Shape {}
    enum Color implements Token { RED, GREEN, BLUE }

    static String describe(Object o) {
        return switch (o) {
            case null -> "null";
            case Integer i when i > 100 -> "big int " + i;
            case Integer i -> "int " + i;
            case String s when s.isEmpty() -> "empty string";
            case String s -> "string " + s.length();
            case Point(int x, int y) when x == y -> "diagonal point " + x;
            case Point(int x, int y) -> "point " + x + "," + y;
            case int[] a -> "int array " + a.length;
            case Color c -> "color " + c.ordinal();
            default -> "other " + o.getClass().getSimpleName();
        };
    }

    static double area(Shape s) {
        return switch (s) {
            case Circle c -> Math.round(Math.PI * c.r() * c.r());
            case Square q -> q.side() * q.side();
        };
    }

    sealed interface Token permits Color, Word {}
    record Word(String text) implements Token {}

    static String token(Token t) {
        return switch (t) {
            case Color.RED -> "red token";
            case Color c -> "other color " + c;
            case Word w -> "word " + w.text();
        };
    }

    static String mood(Color c) {
        return switch (c) {
            case RED -> "angry";
            case Color x when x.name().startsWith("G") -> "envious";
            case Color x -> "calm " + x;
        };
    }

    public static void main(String[] args) throws Throwable {
        MethodHandles.Lookup lk = MethodHandles.lookup();

        // static
        MethodHandle max = lk.findStatic(Math.class, "max", MethodType.methodType(int.class, int.class, int.class));
        System.out.println("type " + max.type() + " " + max);
        int m = (int) max.invokeExact(3, 9);
        System.out.println("max " + m);
        Object boxed = max.invoke((Object) 4, (Object) 2);
        System.out.println("max via invoke " + boxed);
        MethodHandle tw = lk.findStatic(MethodHandleCheck.class, "twice", MethodType.methodType(String.class, String.class));
        System.out.println("twice " + (String) tw.invokeExact("ab"));
        MethodHandle wd = lk.findStatic(MethodHandleCheck.class, "widen", MethodType.methodType(long.class, long.class, double.class));
        long w = (long) wd.invoke(5, 2.5f);
        System.out.println("widen " + w);
        MethodHandle bp = lk.findStatic(MethodHandleCheck.class, "bump", MethodType.methodType(void.class, int.class));
        bp.invokeExact(5);
        System.out.println("counter " + counter);

        // virtual and interface
        MethodHandle concat = lk.findVirtual(String.class, "concat", MethodType.methodType(String.class, String.class));
        System.out.println("concat " + (String) concat.invokeExact("foo", "bar") + " " + concat.type());
        MethodHandle size = lk.findVirtual(List.class, "size", MethodType.methodType(int.class));
        System.out.println("size " + (int) size.invokeExact((List<?>) new ArrayList<>(List.of(1, 2, 3))));
        MethodHandle greet = lk.findVirtual(Base.class, "greet", MethodType.methodType(String.class));
        System.out.println("greet " + (String) greet.invokeExact((Base) new Sub()) + " special " + new Sub().viaSpecial());

        // constructors, and the asType call Caffeine makes before invokeExact
        MethodHandle ctor = lk.findConstructor(StringBuilder.class, MethodType.methodType(void.class, String.class));
        System.out.println("ctor type " + ctor.type());
        StringBuilder sb = (StringBuilder) ctor.invokeExact("xyz");
        System.out.println("built " + sb.reverse());
        MethodHandle asCs = ctor.asType(ctor.type().changeReturnType(CharSequence.class));
        CharSequence cs = (CharSequence) asCs.invokeExact("seq");
        System.out.println("as CharSequence " + cs + " " + asCs.type());
        MethodHandle noArg = lk.findConstructor(ArrayList.class, MethodType.methodType(void.class));
        Object list = noArg.asType(noArg.type().changeReturnType(Object.class)).invokeExact();
        System.out.println("no-arg ctor " + list);

        // fields
        MethodHandle sg = lk.findStaticGetter(MethodHandleCheck.class, "counter", int.class);
        MethodHandle ss = lk.findStaticSetter(MethodHandleCheck.class, "counter", int.class);
        ss.invokeExact(40);
        System.out.println("static field " + (int) sg.invokeExact());
        MethodHandleCheck self = new MethodHandleCheck();
        MethodHandle g = lk.findGetter(MethodHandleCheck.class, "label", String.class);
        MethodHandle st = lk.findSetter(MethodHandleCheck.class, "label", String.class);
        st.invokeExact(self, "changed");
        System.out.println("field " + (String) g.invokeExact(self));

        // unreflect
        Method mm = String.class.getMethod("toUpperCase");
        System.out.println("unreflect " + lk.unreflect(mm).invoke("shout"));
        Constructor<?> c = Point.class.getDeclaredConstructor(int.class, int.class);
        System.out.println("unreflectConstructor " + lk.unreflectConstructor(c).invoke(1, 2));
        Field f = MethodHandleCheck.class.getDeclaredField("label");
        System.out.println("unreflectGetter " + lk.unreflectGetter(f).invoke(self));

        // combinators
        MethodHandle hello = concat.bindTo("hello, ");
        System.out.println("bindTo " + (String) hello.invokeExact("world") + " " + hello.type());
        MethodHandle ins = MethodHandles.insertArguments(max, 1, 50);
        System.out.println("insertArguments " + (int) ins.invokeExact(7) + " " + (int) ins.invokeExact(70));
        MethodHandle drop = MethodHandles.dropArguments(tw, 0, int.class, Object.class);
        System.out.println("dropArguments " + (String) drop.invokeExact(1, (Object) "x", "yz") + " " + drop.type());
        MethodHandle k = MethodHandles.constant(String.class, "always");
        System.out.println("constant " + (String) k.invokeExact());
        MethodHandle id = MethodHandles.identity(int.class);
        System.out.println("identity " + (int) id.invokeExact(17));
        MethodHandle len = lk.findVirtual(String.class, "length", MethodType.methodType(int.class));
        MethodHandle twLen = MethodHandles.filterReturnValue(tw, len);
        System.out.println("filterReturnValue " + (int) twLen.invokeExact("abc"));

        MethodHandle isEmpty = lk.findVirtual(String.class, "isEmpty", MethodType.methodType(boolean.class));
        MethodHandle guard = MethodHandles.guardWithTest(isEmpty, MethodHandles.dropArguments(MethodHandles.constant(String.class, "(empty)"), 0, String.class), tw);
        System.out.println("guardWithTest " + (String) guard.invokeExact("") + " " + (String) guard.invokeExact("ab"));
        MethodHandle swapped = MethodHandles.permuteArguments(concat, concat.type(), 1, 0);
        MethodHandle both = MethodHandles.permuteArguments(concat, MethodType.methodType(String.class, String.class), 0, 0);
        System.out.println("permuteArguments " + (String) swapped.invokeExact("a", "b") + " " + (String) both.invokeExact("ha"));

        MethodHandle loose = MethodHandles.explicitCastArguments(max, MethodType.methodType(Object.class, Object.class, int.class));
        System.out.println("explicitCastArguments " + loose.invoke((Object) 5, 6) + " " + loose.type());

        // invokeWithArguments, and a varargs method
        System.out.println("invokeWithArguments " + max.invokeWithArguments(11, 12) + " " + concat.invokeWithArguments(List.of("a", "b")));
        MethodHandle summer = lk.findStatic(MethodHandleCheck.class, "sum", MethodType.methodType(int.class, int[].class));
        System.out.println("varargs " + (int) summer.invokeExact(new int[] {1, 2, 3}) + " collector " + summer.isVarargsCollector());

        // failures
        try {
            lk.findStatic(Math.class, "nope", MethodType.methodType(int.class));
        } catch (NoSuchMethodException e) {
            System.out.println("missing method: NoSuchMethodException");
        }
        try {
            lk.findGetter(MethodHandleCheck.class, "nope", int.class);
        } catch (NoSuchFieldException e) {
            System.out.println("missing field: NoSuchFieldException");
        }
        try {
            lk.findGetter(MethodHandleCheck.class, "label", int.class);
        } catch (NoSuchFieldException e) {
            System.out.println("wrong field type: NoSuchFieldException");
        }
        MethodHandle loosened = concat.asType(MethodType.methodType(String.class, Object.class, String.class));
        try {
            System.out.println((String) loosened.invokeExact((Object) 42, "x"));
        } catch (ClassCastException e) {
            System.out.println("bad receiver: ClassCastException");
        }
        MethodHandle parse = lk.findStatic(Integer.class, "parseInt", MethodType.methodType(int.class, String.class));
        try {
            int bad = (int) parse.invokeExact("x1");
        } catch (NumberFormatException e) {
            System.out.println("thrown through: " + e.getMessage());
        }

        // findClass and privateLookupIn, as Caffeine uses them
        Class<?> pc = lk.findClass("MethodHandleCheck$Point");
        MethodHandles.Lookup priv = MethodHandles.privateLookupIn(pc, lk);
        System.out.println("findClass " + pc.getSimpleName() + " lookupClass " + priv.lookupClass().getSimpleName());

        // a call site's dynamic invoker follows its target
        MutableCallSite site = new MutableCallSite(MethodType.methodType(String.class));
        MethodHandle dyn = site.dynamicInvoker();
        site.setTarget(MethodHandles.constant(String.class, "one"));
        String a = (String) dyn.invokeExact();
        site.setTarget(MethodHandles.constant(String.class, "two"));
        System.out.println("dynamicInvoker " + a + " " + (String) dyn.invokeExact());

        // pattern switches
        for (Object o : new Object[] {null, 7, 500, "", "four", new Point(3, 3), new Point(1, 2), new int[4], Color.BLUE, 2.5})
            System.out.println("describe " + describe(o));
        System.out.println("area " + area(new Circle(2)) + " " + area(new Square(3)));
        for (Color col : Color.values()) System.out.println("mood " + mood(col));
        for (Token t : new Token[] {Color.RED, Color.GREEN, new Word("hi")}) System.out.println("token " + token(t));
    }
}
