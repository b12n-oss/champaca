import java.lang.invoke.*;
import java.util.*;
import static java.lang.invoke.MethodType.methodType;

// The MethodHandles loop combinators: loop with several clauses, whileLoop,
// doWhileLoop, both countedLoops, iteratedLoop, void loop variables and
// loop arguments, the order clauses run in, an exception out of a body, and
// the JDK's own refusals of malformed clauses.
public class LoopHandleCheck {
    static final List<String> log = new ArrayList<>();
    static final MethodHandles.Lookup L = MethodHandles.lookup();

    static MethodHandle mh(String name, Class<?> r, Class<?>... ps) throws Exception {
        return L.findStatic(LoopHandleCheck.class, name, methodType(r, ps));
    }
    static void row(String name, Object v) { System.out.println(name + ": " + v); }

    // a two-clause loop: i counts, sum adds, stop at i == limit
    static int step_i(int i, int sum, int limit) { log.add("i" + i); return i + 1; }
    static boolean pred_i(int i, int sum, int limit) { return i < limit; }
    static int fini_sum(int i, int sum, int limit) { return sum; }
    static int step_sum(int i, int sum, int limit) { log.add("s" + sum); return sum + i; }
    static int init_i(int limit) { log.add("init_i"); return 0; }
    static int init_sum(int limit) { log.add("init_sum"); return 100; }
    // countedLoop bodies
    static String countBody(String v, int i) { return v + i; }
    static void countVoid(int i) { log.add("c" + i); }
    static int countedRangeBody(int v, int i, String tag) { return v * 10 + i; }
    // iteratedLoop
    static String joinBody(String v, String e) { return v + "[" + e + "]"; }
    static String joinInit() { return ">"; }
    // a body that throws
    static int boom(int v, int i) { if (i == 2) throw new IllegalStateException("at " + i); return v + i; }
    // void-returning steps
    static void tick(int limit) { log.add("tick"); }
    static int countdown(int n, int limit) { return n - 1; }
    static boolean positive(int n, int limit) { return n > 0; }
    static int finiN(int n, int limit) { return n * 1000; }

    public static void main(String[] args) throws Throwable {
        // loop with two clauses: the order of inits, steps and preds
        MethodHandle[] ci = { mh("init_i", int.class, int.class), mh("step_i", int.class, int.class, int.class, int.class),
                              mh("pred_i", boolean.class, int.class, int.class, int.class), mh("fini_sum", int.class, int.class, int.class, int.class) };
        MethodHandle[] cs = { mh("init_sum", int.class, int.class), mh("step_sum", int.class, int.class, int.class, int.class) };
        MethodHandle two = MethodHandles.loop(ci, cs);
        row("loop type", two.type());
        row("loop(3)", (int) two.invokeExact(3));
        row("order", log);
        log.clear();
        row("loop(0)", (int) two.invokeExact(0));
        row("order(0)", log);
        log.clear();

        // whileLoop and doWhileLoop counting an argument down to 0
        MethodHandle wBody = mh("countdown", int.class, int.class, int.class);
        MethodHandle whileH = MethodHandles.whileLoop(MethodHandles.identity(int.class), mh("positive", boolean.class, int.class, int.class), wBody);
        row("whileLoop type", whileH.type());
        row("whileLoop(5)", (int) whileH.invokeExact(5));
        row("whileLoop(-3)", (int) whileH.invokeExact(-3));
        MethodHandle doWhile = MethodHandles.doWhileLoop(MethodHandles.identity(int.class), wBody, mh("positive", boolean.class, int.class, int.class));
        row("doWhileLoop(5)", (int) doWhile.invokeExact(5));
        row("doWhileLoop(-3)", (int) doWhile.invokeExact(-3));

        // countedLoop(iterations, init, body)
        MethodHandle counted = MethodHandles.countedLoop(MethodHandles.constant(int.class, 4),
                MethodHandles.constant(String.class, "v"), mh("countBody", String.class, String.class, int.class));
        row("countedLoop", (String) counted.invokeExact());
        MethodHandle countedZero = MethodHandles.countedLoop(MethodHandles.constant(int.class, 0),
                MethodHandles.constant(String.class, "v"), mh("countBody", String.class, String.class, int.class));
        row("countedLoop zero", (String) countedZero.invokeExact());
        MethodHandle countedNeg = MethodHandles.countedLoop(MethodHandles.constant(int.class, -2),
                MethodHandles.constant(String.class, "v"), mh("countBody", String.class, String.class, int.class));
        row("countedLoop negative", (String) countedNeg.invokeExact());
        MethodHandle countedVoid = MethodHandles.countedLoop(MethodHandles.constant(int.class, 3), null, mh("countVoid", void.class, int.class));
        row("countedLoop void type", countedVoid.type());
        countedVoid.invokeExact();
        row("countedLoop void", log);
        log.clear();
        // countedLoop(start, end, init, body) with a loop argument
        MethodHandle range = MethodHandles.countedLoop(
                MethodHandles.dropArguments(MethodHandles.constant(int.class, 2), 0, String.class),
                MethodHandles.dropArguments(MethodHandles.constant(int.class, 6), 0, String.class),
                MethodHandles.dropArguments(MethodHandles.constant(int.class, 0), 0, String.class),
                mh("countedRangeBody", int.class, int.class, int.class, String.class));
        row("countedLoop range type", range.type());
        row("countedLoop range", (int) range.invokeExact("t"));

        // iteratedLoop over a List and with an explicit iterator
        MethodHandle joined = MethodHandles.iteratedLoop(null, mh("joinInit", String.class), mh("joinBody", String.class, String.class, String.class));
        MethodHandle overList = joined.asType(methodType(String.class, Iterable.class));
        row("iteratedLoop", (String) overList.invokeExact((Iterable) List.of("a", "b", "c")));
        row("iteratedLoop empty", (String) overList.invokeExact((Iterable) List.of()));

        // a body that throws ends the loop with the exception
        MethodHandle throwing = MethodHandles.countedLoop(MethodHandles.constant(int.class, 5),
                MethodHandles.constant(int.class, 0), mh("boom", int.class, int.class, int.class));
        try { row("throws", (int) throwing.invokeExact()); } catch (IllegalStateException e) { row("throws", e); }

        // a void clause (no variable) alongside a counting one
        MethodHandle[] tickClause = { mh("tick", void.class, int.class) };
        MethodHandle[] down = { MethodHandles.identity(int.class), mh("countdown", int.class, int.class, int.class),
                                mh("positive", boolean.class, int.class, int.class), mh("finiN", int.class, int.class, int.class) };
        MethodHandle withVoid = MethodHandles.loop(tickClause, down);
        row("void clause type", withVoid.type());
        row("void clause(2)", (int) withVoid.invokeExact(2));
        row("void clause log", log);
        log.clear();

        // refusals from the JDK's own checks
        refuse("no clauses", () -> MethodHandles.loop());
        refuse("null clause", () -> MethodHandles.loop((MethodHandle[]) null));
        refuse("empty clause", () -> MethodHandles.loop(new MethodHandle[0]));
        refuse("init/step mismatch", () -> MethodHandles.loop(new MethodHandle[] { MethodHandles.constant(int.class, 0),
                MethodHandles.identity(String.class) }));
        refuse("pred not boolean", () -> MethodHandles.loop(new MethodHandle[] { null, null, MethodHandles.constant(int.class, 1) }));
        refuse("while body/init", () -> MethodHandles.whileLoop(MethodHandles.constant(String.class, "x"),
                mh("positive", boolean.class, int.class, int.class), wBody));
        refuse("counted iterations type", () -> MethodHandles.countedLoop(MethodHandles.constant(long.class, 3L), null, mh("countVoid", void.class, int.class)));
        refuse("iterated body null", () -> MethodHandles.iteratedLoop(null, null, null));
    }

    interface Make { Object make() throws Throwable; }
    static void refuse(String name, Make m) {
        try { row(name, m.make()); } catch (Throwable t) { row(name, t); }
    }
}
