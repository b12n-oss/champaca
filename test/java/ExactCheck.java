import java.lang.invoke.*;
import java.util.*;

// MethodHandle.invokeExact checks the call site's type against the handle's
// type(), exactly: any difference, a subtype or a boxing one included, is
// WrongMethodTypeException with the JDK's text, while invoke adapts.
public class ExactCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String label, Callable r) {
        try { System.out.println(label + ": " + r.call()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + ": " + e.getMessage()); }
    }
    static int add(int a, int b) { return a + b; }
    static long wide(int a) { return a * 10L; }
    static String hello(Object o) { return "hello " + o; }
    static void sink(String s) { System.out.println("sink " + s); }
    static int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }
    static Integer boxed(Integer a) { return a + 1; }
    String name = "inst";
    String greet(String who) { return name + " greets " + who; }
    ExactCheck() {}
    ExactCheck(String n) { name = n; }

    public static void main(String[] args) throws Throwable {
        MethodHandles.Lookup L = MethodHandles.lookup();
        Class<?> me = ExactCheck.class;
        MethodHandle add = L.findStatic(me, "add", MethodType.methodType(int.class, int.class, int.class));
        MethodHandle wide = L.findStatic(me, "wide", MethodType.methodType(long.class, int.class));
        MethodHandle hello = L.findStatic(me, "hello", MethodType.methodType(String.class, Object.class));
        MethodHandle sink = L.findStatic(me, "sink", MethodType.methodType(void.class, String.class));
        MethodHandle sum = L.findStatic(me, "sum", MethodType.methodType(int.class, int[].class));
        MethodHandle boxed = L.findStatic(me, "boxed", MethodType.methodType(Integer.class, Integer.class));
        MethodHandle greet = L.findVirtual(me, "greet", MethodType.methodType(String.class, String.class));
        MethodHandle ctor = L.findConstructor(me, MethodType.methodType(void.class, String.class));
        MethodHandle nameG = L.findGetter(me, "name", String.class);
        ExactCheck obj = new ExactCheck();

        t("exact (II)I", () -> (int) add.invokeExact(2, 3));
        t("exact result as long", () -> { long r = (long) add.invokeExact(2, 3); return r; });
        t("exact result as Object", () -> { Object r = add.invokeExact(2, 3); return r; });
        t("exact result dropped", () -> { add.invokeExact(2, 3); return "dropped"; });
        t("exact long arg", () -> (int) add.invokeExact(2L, 3));
        t("exact boxed arg", () -> (int) add.invokeExact((Integer) 2, 3));
        t("exact too few", () -> (int) add.invokeExact(2));
        t("exact too many", () -> (int) add.invokeExact(2, 3, 4));
        t("invoke adapts", () -> { long r = (long) add.invoke(2, 3); return r; });
        t("invoke boxed arg", () -> (int) add.invoke((Integer) 2, 3));
        t("exact (I)J", () -> (long) wide.invokeExact(4));
        t("exact (I)J as int", () -> (int) wide.invokeExact(4));
        t("exact Object arg", () -> (String) hello.invokeExact((Object) "w"));
        t("exact String arg", () -> (String) hello.invokeExact("w"));
        t("exact Object result", () -> (Object) hello.invokeExact((Object) "w"));
        t("exact void", () -> { sink.invokeExact("s"); return "ok"; });
        t("exact void as Object", () -> (Object) sink.invokeExact("s"));
        t("exact varargs array", () -> (int) sum.invokeExact(new int[] {1, 2, 3}));
        t("exact varargs spread", () -> (int) sum.invokeExact(1, 2, 3));
        t("invoke varargs spread", () -> (int) sum.invoke(1, 2, 3));
        t("exact boxed type", () -> (Integer) boxed.invokeExact((Integer) 4));
        t("exact boxed with int", () -> (Integer) boxed.invokeExact(4));
        t("exact virtual", () -> (String) greet.invokeExact(obj, "you"));
        t("exact virtual Object recv", () -> (String) greet.invokeExact((Object) obj, "you"));
        t("exact constructor", () -> ((ExactCheck) ctor.invokeExact("made")).name);
        t("exact constructor as Object", () -> ((ExactCheck) (Object) ctor.invokeExact("made")).name);
        t("exact getter", () -> (String) nameG.invokeExact(obj));
        MethodHandle bound = greet.bindTo(obj);
        t("exact bound", () -> (String) bound.invokeExact("b"));
        t("exact bound wrong", () -> (String) bound.invokeExact(obj, "b"));
        MethodHandle asT = add.asType(MethodType.methodType(Object.class, Object.class, Object.class));
        t("exact asType", () -> (Object) asT.invokeExact((Object) 5, (Object) 6));
        t("exact asType original", () -> (int) asT.invokeExact(5, 6));
        MethodHandle ins = MethodHandles.insertArguments(add, 0, 10);
        t("exact inserted", () -> (int) ins.invokeExact(1));
        MethodHandle drop = MethodHandles.dropArguments(add, 0, String.class);
        t("exact dropped arg", () -> (int) drop.invokeExact("x", 1, 2));
        t("exact dropped arg wrong", () -> (int) drop.invokeExact((Object) "x", 1, 2));
        MethodHandle cst = MethodHandles.constant(String.class, "k");
        t("exact constant", () -> (String) cst.invokeExact());
        t("exact constant as Object", () -> (Object) cst.invokeExact());
        MethodHandle nullMh = null;
        t("exact null handle", () -> (int) nullMh.invokeExact(1, 2));
        t("exact invoker", () -> (int) MethodHandles.exactInvoker(add.type()).invokeExact(add, 1, 2));
        t("exact invoker wrong", () -> (int) MethodHandles.exactInvoker(add.type()).invokeExact(wide, 1, 2));
        MutableCallSite cs = new MutableCallSite(add);
        MethodHandle dyn = cs.dynamicInvoker();
        t("exact dynamicInvoker", () -> (int) dyn.invokeExact(7, 8));
        t("exact dynamicInvoker wrong", () -> (long) dyn.invokeExact(7, 8));
        MethodHandle arrG = MethodHandles.arrayElementGetter(String[].class);
        t("exact array getter", () -> (String) arrG.invokeExact(new String[] {"a0"}, 0));
        t("exact array getter Object", () -> (Object) arrG.invokeExact(new String[] {"a0"}, 0));
        // a list built through JDK code that itself uses invokeExact
        List<Object> got = new ArrayList<>();
        for (int i = 0; i < 3; i++) got.add((int) add.invokeExact(i, i));
        t("exact in a loop", () -> got);
        t("record toString", () -> new P(1, "q").toString());
        t("record equals", () -> new P(1, "q").equals(new P(1, "q")));
        t("record hashCode", () -> new P(1, "q").hashCode() == new P(1, "q").hashCode());
        t("string concat", () -> "a" + args.length + 'c' + 2.5 + obj.name);
        // the site's type in the message is MethodType.toString's: simple names
        class Local {}
        Object anon = new Object() {};
        t("exact local class arg", () -> (int) add.invokeExact((Local) null, 2));
        t("exact local array arg", () -> (int) add.invokeExact((Local[]) null, 2));
        t("exact dollar class arg", () -> (int) add.invokeExact((Dollar$Name) null, 2));
        t("exact local result", () -> (Local) add.invokeExact(1, 2));
        t("exact nested arg", () -> (int) add.invokeExact((Map.Entry) null, 2));
        t("exact 2d array arg", () -> (int) add.invokeExact((int[][]) null, 2));
        t("anonymous simple name", () -> "[" + anon.getClass().getSimpleName() + "]");
        var anonTyped = new Object() {};
        t("exact anonymous arg", () -> (int) add.invokeExact(anonTyped, 2));
        t("asType local", () -> add.asType(MethodType.methodType(int.class, Local.class, int.class)));
        t("asType dollar", () -> add.asType(MethodType.methodType(int.class, Dollar$Name.class, int.class)));
        t("exact invoker local", () -> (int) MethodHandles.exactInvoker(
            MethodType.methodType(int.class, Local.class, int.class)).invokeExact(add, (Local) null, 2));
        MethodHandle str0 = MethodHandles.constant(String.class, "s");
        MethodHandle cleanupInt = MethodHandles.dropArguments(MethodHandles.constant(int.class, 0), 0, Throwable.class, int.class);
        t("tryFinally return mismatch", () -> MethodHandles.tryFinally(str0, cleanupInt));
        MethodHandle cleanupStr = MethodHandles.dropArguments(MethodHandles.constant(String.class, "c"), 0, Throwable.class, String.class);
        MethodHandle int0 = MethodHandles.constant(int.class, 1);
        t("tryFinally return mismatch 2", () -> MethodHandles.tryFinally(int0, cleanupStr));
    }
    record P(int a, String b) {}
}
class Dollar$Name {}
