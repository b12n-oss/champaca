import java.lang.invoke.*;
import java.util.*;

// MethodHandles.zero/empty and the combinator surface past MethodHandleCheck:
// filters, folds, collectors, spreaders, array accessors, invokers and the
// exception combinators, each against the JVM's results and exception texts.
public class MethodHandleCombinatorCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String label, Callable r) {
        try { System.out.println(label + ": " + r.call()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + ": " + e.getMessage()); }
    }
    static int add(int a, int b) { return a + b; }
    static int addL(long a, int b) { return (int) a + b; }
    static int neg(int a) { return -a; }
    static boolean pos(int a) { return a > 0; }
    static int catcher(RuntimeException e, int a) { return -100; }
    static int boom(int a) { throw new RuntimeException("x" + a); }
    static int h0(RuntimeException e) { return -9; }
    static int sum(int[] xs) { int s = 0; for (int x : xs) s += x; return s; }
    static int fin(Throwable t, int r, int a) { System.out.println("fin r=" + r); return r + 1000; }
    static int fin2(Throwable t, int r, int a) { System.out.println("fin2 t=" + t + " r=" + r); throw new IllegalStateException("cleanup"); }
    static int finBad(String s, int r, int a) { return 0; }
    static long finLong(Throwable t, int r, int a) { return 1L; }
    static int h2(RuntimeException e, int a, int b) { return 0; }
    static long hL(RuntimeException e) { return 1L; }
    static int h00() { return 0; }
    static int hStr(String s) { return 0; }
    static void note(int a) { System.out.println("note " + a); }
    static void noteS(String s) { System.out.println("noteS " + s); }
    static int len2(String s, int b) { return s.length() + b; }

    public static void main(String[] args) throws Throwable {
        MethodHandles.Lookup L = MethodHandles.lookup();
        Class<?> me = MethodHandleCombinatorCheck.class;
        MethodType ii_i = MethodType.methodType(int.class, int.class, int.class);
        MethodHandle add = L.findStatic(me, "add", ii_i);
        MethodHandle addL = L.findStatic(me, "addL", MethodType.methodType(int.class, long.class, int.class));
        MethodHandle neg = L.findStatic(me, "neg", MethodType.methodType(int.class, int.class));
        MethodHandle pos = L.findStatic(me, "pos", MethodType.methodType(boolean.class, int.class));

        // zero and empty
        System.out.println((int) MethodHandles.zero(int.class).invoke());
        System.out.println((Object) MethodHandles.zero(String.class).invoke());
        System.out.println((boolean) MethodHandles.zero(boolean.class).invoke());
        System.out.println((double) MethodHandles.zero(double.class).invoke());
        MethodHandle zv = MethodHandles.zero(void.class);
        zv.invoke(); System.out.println("zero-void type " + zv.type());
        MethodHandle empty = MethodHandles.empty(MethodType.methodType(int.class, String.class));
        System.out.println((int) empty.invoke("x"));
        t("zero-null", () -> MethodHandles.zero(null));
        t("empty-null", () -> MethodHandles.empty(null));
        t("empty-void", () -> { MethodHandle h = MethodHandles.empty(MethodType.methodType(void.class, int.class)); h.invoke(3); return h.type(); });
        t("empty-type", () -> MethodHandles.empty(MethodType.methodType(String.class, int.class)).type());

        // constant and identity, the JDK's conversions and refusals
        t("constant", () -> (int) MethodHandles.constant(int.class, 42).invoke());
        t("constant-widen", () -> { MethodHandle h = MethodHandles.constant(long.class, 5); return (long) h.invoke(); });
        t("constant-narrow", () -> MethodHandles.constant(int.class, 5L));
        t("constant-ref", () -> MethodHandles.constant(Object.class, "s").invoke());
        t("constant-refbad", () -> MethodHandles.constant(String.class, 5));
        t("constant-nullref", () -> MethodHandles.constant(String.class, null).invoke());
        t("constant-nullprim", () -> MethodHandles.constant(int.class, null));
        t("constant-nulltype", () -> MethodHandles.constant(null, "x"));
        t("constant-void", () -> MethodHandles.constant(void.class, null));
        t("identity-void", () -> MethodHandles.identity(void.class));
        t("identity-null", () -> MethodHandles.identity(null));
        t("identity-prim", () -> (int) MethodHandles.identity(int.class).invoke(7));

        // argument combinators
        t("filterArguments", () -> (int) MethodHandles.filterArguments(add, 0, neg).invoke(3, 10));
        t("filterArguments-null", () -> (int) MethodHandles.filterArguments(add, 0, null, neg).invoke(3, 10));
        t("foldArguments", () -> (int) MethodHandles.foldArguments(add, neg).invoke(5));
        t("foldArguments-pos", () -> (int) MethodHandles.foldArguments(MethodHandles.dropArguments(add, 0, int.class), 1, neg).invoke(9, 5));
        t("collectArguments", () -> (int) MethodHandles.collectArguments(add, 1, neg).invoke(1, 2));
        t("collectArguments-2", () -> (int) MethodHandles.collectArguments(neg, 0, add).invoke(2, 3));
        MethodHandle note = L.findStatic(me, "note", MethodType.methodType(void.class, int.class));
        t("foldArguments-void", () -> (int) MethodHandles.foldArguments(add, note).invoke(5, 2));
        t("collectArguments-void", () -> (int) MethodHandles.collectArguments(add, 0, note).invoke(9, 2, 3));
        t("foldArguments-void-het", () -> { MethodHandle len2 = L.findStatic(me, "len2", MethodType.methodType(int.class, String.class, int.class));
            MethodHandle noteS = L.findStatic(me, "noteS", MethodType.methodType(void.class, String.class));
            return (int) MethodHandles.foldArguments(len2, noteS).invoke("abc", 4); });

        // spreaders and collectors
        t("asSpreader", () -> (int) add.asSpreader(int[].class, 2).invoke(new int[]{2,3}));
        t("spreader-shortarray", () -> (int) add.asSpreader(int[].class, 2).invoke(new int[]{1}));
        t("spreader-nullarray", () -> (int) add.asSpreader(int[].class, 2).invoke((int[]) null));
        t("spreader-badcount", () -> add.asSpreader(int[].class, 3));
        t("spreader-badtype", () -> add.asSpreader(String[].class, 2));
        t("asCollector", () -> { MethodHandle f = L.findStatic(me, "sum", MethodType.methodType(int.class, int[].class)); return (int) f.asCollector(int[].class, 3).invoke(1,2,3); });
        t("collector-badtype", () -> add.asCollector(String[].class, 2));

        // array accessors
        t("arrayElementGetter", () -> (int) MethodHandles.arrayElementGetter(int[].class).invoke(new int[]{9,8}, 1));
        t("arrayElementSetter", () -> { MethodHandle s = MethodHandles.arrayElementSetter(int[].class); int[] xs = {0,0}; s.invoke(xs, 0, 7); return xs[0]; });
        t("arrayLength", () -> (int) MethodHandles.arrayLength(int[].class).invoke(new int[3]));
        t("arrayConstructor", () -> ((int[]) MethodHandles.arrayConstructor(int[].class).invoke(4)).length);
        t("aget-null", () -> (int) MethodHandles.arrayElementGetter(int[].class).invoke((int[]) null, 0));
        t("aget-oob", () -> (int) MethodHandles.arrayElementGetter(int[].class).invoke(new int[2], 5));
        t("aset-badval", () -> { MethodHandles.arrayElementSetter(Object[].class).invoke(new String[1], 0, Integer.valueOf(3)); return "ok"; });

        // invokers
        t("invoker", () -> (int) MethodHandles.invoker(ii_i).invoke(add, 2, 3));
        t("invoker-adapts", () -> (int) MethodHandles.invoker(ii_i).invoke(addL, 2, 3));
        t("exactInvoker", () -> (int) MethodHandles.exactInvoker(ii_i).invoke(add, 2, 3));
        t("exactInvoker-mismatch", () -> (int) MethodHandles.exactInvoker(ii_i).invoke(addL, 2, 3));
        t("spreadInvoker", () -> (int) MethodHandles.spreadInvoker(ii_i, 0).invoke(add, new Object[]{2,3}));
        t("spreadInvoker-lead", () -> (int) MethodHandles.spreadInvoker(ii_i, 1).invoke(add, 4, new Object[]{3}));
        t("asType-reject", () -> add.asType(MethodType.methodType(int.class, int.class, long.class)));

        // exception combinators
        t("catchException", () -> (int) MethodHandles.catchException(neg,
            RuntimeException.class, L.findStatic(me, "catcher", MethodType.methodType(int.class, RuntimeException.class, int.class))).invoke(4));
        t("catchException-prefix", () -> { MethodHandle b = L.findStatic(me, "boom", MethodType.methodType(int.class, int.class));
            MethodHandle h = L.findStatic(me, "h0", MethodType.methodType(int.class, RuntimeException.class));
            return (int) MethodHandles.catchException(b, RuntimeException.class, h).invoke(4); });
        t("catchException-miss", () -> { MethodHandle b = L.findStatic(me, "boom", MethodType.methodType(int.class, int.class));
            MethodHandle h = L.findStatic(me, "h0", MethodType.methodType(int.class, RuntimeException.class));
            try { return (int) MethodHandles.catchException(b, IllegalStateException.class, h).invoke(4); }
            catch (RuntimeException e) { return "through: " + e.getMessage(); }});
        t("throwException", () -> { MethodHandle th = MethodHandles.throwException(int.class, RuntimeException.class);
            try { int x = (int) th.invoke(new RuntimeException("boom")); return x; } catch (RuntimeException e) { return "threw " + e.getMessage(); }});
        t("throwException-type", () -> MethodHandles.throwException(int.class, RuntimeException.class).type());
        t("tryFinally", () -> (int) MethodHandles.tryFinally(neg,
            L.findStatic(me, "fin", MethodType.methodType(int.class, Throwable.class, int.class, int.class))).invoke(6));
        t("tryFinally-throw", () -> { MethodHandle b = L.findStatic(me, "boom", MethodType.methodType(int.class, int.class));
            MethodHandle f = L.findStatic(me, "fin", MethodType.methodType(int.class, Throwable.class, int.class, int.class));
            try { return (int) MethodHandles.tryFinally(b, f).invoke(6); } catch (RuntimeException e) { return "rethrew " + e.getMessage(); }});

        // review round: the edges the first version missed
        t("tryFinally-cleanup-throws", () -> { MethodHandle f2 = L.findStatic(me, "fin2", MethodType.methodType(int.class, Throwable.class, int.class, int.class));
            return (int) MethodHandles.tryFinally(neg, f2).invoke(6); });
        t("spreader-null-0", () -> (int) add.asSpreader(int[].class, 0).invoke(2, 3, (int[]) null));
        t("spreader-len3-0", () -> (int) add.asSpreader(int[].class, 0).invoke(2, 3, new int[3]));
        t("spreadInvoker-badcount", () -> MethodHandles.spreadInvoker(ii_i, 5));
        t("spreadInvoker-neg", () -> MethodHandles.spreadInvoker(ii_i, -1));
        t("spreadInvoker-null", () -> (int) MethodHandles.spreadInvoker(ii_i, 2).invoke(add, 2, 3, (Object[]) null));
        t("spreadInvoker-null0", () -> (int) MethodHandles.spreadInvoker(MethodType.methodType(int.class, int.class, int.class), 2).invoke(add, 2, 3, (Object[]) null));
        t("spreadInvoker-short", () -> (int) MethodHandles.spreadInvoker(ii_i, 0).invoke(add, new Object[]{1}));
        MethodHandle h0h = L.findStatic(me, "h0", MethodType.methodType(int.class, RuntimeException.class));
        t("catch-h2", () -> MethodHandles.catchException(neg, RuntimeException.class,
            L.findStatic(me, "h2", MethodType.methodType(int.class, RuntimeException.class, int.class, int.class))));
        t("catch-rettype", () -> MethodHandles.catchException(neg, RuntimeException.class,
            L.findStatic(me, "hL", MethodType.methodType(long.class, RuntimeException.class))));
        t("catch-h0args", () -> MethodHandles.catchException(neg, RuntimeException.class,
            L.findStatic(me, "h00", MethodType.methodType(int.class))));
        t("catch-badexc", () -> MethodHandles.catchException(neg, (Class) String.class, h0h));
        t("catch-wrongparam", () -> MethodHandles.catchException(neg, RuntimeException.class,
            L.findStatic(me, "hStr", MethodType.methodType(int.class, String.class))));
        t("tryFinally-badfirst", () -> MethodHandles.tryFinally(neg,
            L.findStatic(me, "finBad", MethodType.methodType(int.class, String.class, int.class, int.class))));
        t("tryFinally-rettype", () -> MethodHandles.tryFinally(neg,
            L.findStatic(me, "finLong", MethodType.methodType(long.class, Throwable.class, int.class, int.class))));
        t("collector-objclass", () -> add.asCollector(Object.class, 1));
        t("collect-oob", () -> MethodHandles.collectArguments(neg, 5, neg));
        t("collect-neg", () -> MethodHandles.collectArguments(neg, -1, neg));
        t("collect-void-oob", () -> MethodHandles.collectArguments(neg, 2, note));
        t("asType-constable", () -> neg.asType(MethodType.methodType(int.class, java.lang.constant.Constable.class)).type());
        t("asType-numbool", () -> MethodHandles.zero(boolean.class).asType(MethodType.methodType(Number.class)));
        t("asType-intLong", () -> neg.asType(MethodType.methodType(Long.class, int.class)));
        t("aget-null-bool", () -> MethodHandles.arrayElementGetter(boolean[].class).invoke((boolean[]) null, 0));
        t("aget-null-long", () -> MethodHandles.arrayElementGetter(long[].class).invoke((long[]) null, 0));
        t("aset-null-byte", () -> { MethodHandles.arrayElementSetter(byte[].class).invoke((byte[]) null, 0, (byte) 1); return "ok"; });
        t("arrayLength-null", () -> MethodHandles.arrayLength(int[].class).invoke((int[]) null));
    }
}
