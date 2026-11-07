import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static java.lang.foreign.ValueLayout.*;

// FFM upcalls: Linker.upcallStub makes a function pointer that calls a Java
// method handle, here passed to C (target/libffitest's ffi_apply_* functions,
// test/native/ffitest.c, which bb test builds) and to libc's qsort, against
// the JVM's results and texts. Upcalls also come from Java threads at once,
// from a thread C started (attached as HotSpot does: a daemon Thread-N in
// group main, one Thread for the C thread's life), and take and give structs.
public class UpcallCheck {
    interface Body { Object run() throws Throwable; }
    static void row(String label, Body b) {
        String r;
        try { r = String.valueOf(b.run()); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(label + ": " + r);
    }
    static final Linker L = Linker.nativeLinker();
    static final SymbolLookup LIB;
    static { System.loadLibrary("ffitest"); LIB = SymbolLookup.loaderLookup(); }
    static MethodHandle down(String name, FunctionDescriptor fd) { return L.downcallHandle(LIB.findOrThrow(name), fd); }
    static MethodHandle mh(String name, MethodType t) throws Exception { return MethodHandles.lookup().findStatic(UpcallCheck.class, name, t); }

    static int twice(int x) { return x * 2; }
    static long plusBig(long x) { return x + 10_000_000_000L; }
    static double hyp(double a, double b) { return Math.sqrt(a * a + b * b); }
    static float half(float x) { return x / 2; }
    static boolean not(boolean b) { return !b; }
    static byte next(byte c) { return (byte) (c + 1); }
    static short neg(short s) { return (short) -s; }
    static MemorySegment same(MemorySegment p) { return p; }
    static int calls;
    static void bump() { calls++; }
    static int square(int i) { return i * i; }
    static long mixed(int a, double b, long c) { return a + (long) b + c; }
    static int compare(MemorySegment a, MemorySegment b) {
        return Integer.compare(a.reinterpret(4).get(JAVA_INT, 0), b.reinterpret(4).get(JAVA_INT, 0));
    }
    static int reentrant(int x) {
        // an upcall that downcalls back into C, which upcalls again
        if (x <= 0) return 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment f = L.upcallStub(mh("reentrant", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_INT, JAVA_INT), a);
            return x + (int) down("ffi_apply_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)).invokeExact(f, x - 1);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }
    static int throwsChecked(int x) throws Exception { return x; }
    static final AtomicInteger hits = new AtomicInteger();
    static int count(int i) { hits.incrementAndGet(); return i; }
    static final List<Thread> seen = new ArrayList<>();
    static final List<String> seenText = new ArrayList<>();
    static int who(int i) {
        Thread t = Thread.currentThread();
        seen.add(t);
        seenText.add(t.getName() + " " + t.getThreadGroup().getName() + " daemon=" + t.isDaemon() + " listed=" + Thread.getAllStackTraces().containsKey(t));
        return i;
    }
    static final StructLayout P2 = MemoryLayout.structLayout(JAVA_INT, JAVA_INT);
    static final StructLayout D4 = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    static final StructLayout L3 = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);
    static final StructLayout L2 = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG);
    static final StructLayout F3 = MemoryLayout.structLayout(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    static final StructLayout CD = MemoryLayout.structLayout(JAVA_BYTE, MemoryLayout.paddingLayout(7), JAVA_DOUBLE);
    static int sumP2(MemorySegment p) { return p.get(JAVA_INT, 0) * 10 + p.get(JAVA_INT, 4); }
    static double sumD4(MemorySegment d) { double s = 0; for (int i = 0; i < 4; i++) s += d.getAtIndex(JAVA_DOUBLE, i) * (i + 1); return s; }
    static long sumL3(MemorySegment l) { return l.getAtIndex(JAVA_LONG, 0) + l.getAtIndex(JAVA_LONG, 1) * 10 + l.getAtIndex(JAVA_LONG, 2) * 100; }
    static final Arena RESULTS = Arena.ofAuto();
    static MemorySegment makeP2(int x) { MemorySegment p = RESULTS.allocate(P2); p.set(JAVA_INT, 0, x); p.set(JAVA_INT, 4, x + 1); return p; }
    static MemorySegment makeD4(double a) { MemorySegment d = RESULTS.allocate(D4); for (int i = 0; i < 4; i++) d.setAtIndex(JAVA_DOUBLE, i, a * (i + 1)); return d; }
    static MemorySegment makeL3(long a) { MemorySegment l = RESULTS.allocate(L3); for (int i = 0; i < 3; i++) l.setAtIndex(JAVA_LONG, i, a + i); return l; }
    static MemorySegment makeL2(long a) { MemorySegment l = RESULTS.allocate(L2); l.setAtIndex(JAVA_LONG, 0, a); l.setAtIndex(JAVA_LONG, 1, -a); return l; }
    static MemorySegment makeF3(float a) { MemorySegment f = RESULTS.allocate(F3); for (int i = 0; i < 3; i++) f.setAtIndex(JAVA_FLOAT, i, a + i); return f; }
    static MemorySegment makeCD(byte c) { MemorySegment r = RESULTS.allocate(CD); r.set(JAVA_BYTE, 0, (byte) (c + 1)); r.set(JAVA_DOUBLE, 8, 0.5); return r; }
    static MethodHandle mh1(String name, Class<?> r, Class<?> p) throws Exception { return mh(name, MethodType.methodType(r, p)); }

    public static void main(String[] args) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            row("int", () -> (int) down("ffi_apply_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
                .invokeExact(L.upcallStub(mh("twice", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_INT, JAVA_INT), a), 21));
            row("long", () -> (long) down("ffi_apply_long", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG))
                .invokeExact(L.upcallStub(mh("plusBig", MethodType.methodType(long.class, long.class)), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG), a), 5L));
            row("double", () -> (double) down("ffi_apply_double2", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE))
                .invokeExact(L.upcallStub(mh("hyp", MethodType.methodType(double.class, double.class, double.class)), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE), a), 3.0, 4.0));
            row("float", () -> (float) down("ffi_apply_float", FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, JAVA_FLOAT))
                .invokeExact(L.upcallStub(mh("half", MethodType.methodType(float.class, float.class)), FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT), a), 5.0f));
            row("boolean", () -> (boolean) down("ffi_apply_bool", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_BOOLEAN))
                .invokeExact(L.upcallStub(mh("not", MethodType.methodType(boolean.class, boolean.class)), FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_BOOLEAN), a), true));
            row("byte", () -> (byte) down("ffi_apply_char", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_BYTE))
                .invokeExact(L.upcallStub(mh("next", MethodType.methodType(byte.class, byte.class)), FunctionDescriptor.of(JAVA_BYTE, JAVA_BYTE), a), (byte) 'a'));
            row("short", () -> (short) down("ffi_apply_short", FunctionDescriptor.of(JAVA_SHORT, ADDRESS, JAVA_SHORT))
                .invokeExact(L.upcallStub(mh("neg", MethodType.methodType(short.class, short.class)), FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT), a), (short) 300));
            row("address", () -> {
                MemorySegment p = a.allocate(8);
                MemorySegment r = (MemorySegment) down("ffi_apply_ptr", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
                    .invokeExact(L.upcallStub(mh("same", MethodType.methodType(MemorySegment.class, MemorySegment.class)), FunctionDescriptor.of(ADDRESS, ADDRESS), a), p);
                return r.address() == p.address();
            });
            row("void", () -> {
                down("ffi_call_void", FunctionDescriptor.ofVoid(ADDRESS))
                    .invokeExact(L.upcallStub(mh("bump", MethodType.methodType(void.class)), FunctionDescriptor.ofVoid(), a));
                return calls;
            });
            row("called in a loop", () -> (int) down("ffi_call_n", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
                .invokeExact(L.upcallStub(mh("square", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_INT, JAVA_INT), a), 10));
            row("mixed", () -> (long) down("ffi_apply_mixed", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_LONG))
                .invokeExact(L.upcallStub(mh("mixed", MethodType.methodType(long.class, int.class, double.class, long.class)),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_DOUBLE, JAVA_LONG), a), 1, 2.5, 3L));
            row("qsort", () -> {
                int[] xs = {5, 3, 9, 1, 7, 2};
                MemorySegment arr = a.allocateFrom(JAVA_INT, xs);
                MethodHandle qsort = L.downcallHandle(L.defaultLookup().findOrThrow("qsort"), FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
                MemorySegment cmp = L.upcallStub(mh("compare", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), a);
                qsort.invokeExact(arr, (long) xs.length, 4L, cmp);
                return Arrays.toString(arr.toArray(JAVA_INT));
            });
            row("reentrant", () -> reentrant(4));
            row("lambda target", () -> {
                MethodHandle t = MethodHandles.lookup().findVirtual(java.util.function.IntUnaryOperator.class, "applyAsInt", MethodType.methodType(int.class, int.class))
                    .bindTo((java.util.function.IntUnaryOperator) x -> x + 100);
                return (int) down("ffi_apply_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
                    .invokeExact(L.upcallStub(t, FunctionDescriptor.of(JAVA_INT, JAVA_INT), a), 1);
            });
            row("stub is native, size 0", () -> {
                MemorySegment s = L.upcallStub(mh("twice", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_INT, JAVA_INT), a);
                return s.isNative() + " " + s.byteSize() + " " + (s.address() != 0);
            });
            row("wrong type", () -> L.upcallStub(mh("twice", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_LONG, JAVA_INT), a));
            row("checked exception", () -> L.upcallStub(mh("throwsChecked", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_INT, JAVA_INT), a));
            row("struct argument, 8 bytes", () -> (int) down("ffi_apply_p2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
                .invokeExact(L.upcallStub(mh1("sumP2", int.class, MemorySegment.class), FunctionDescriptor.of(JAVA_INT, P2), a), 3, 4));
            row("struct argument, four doubles", () -> (double) down("ffi_apply_d4", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE))
                .invokeExact(L.upcallStub(mh1("sumD4", double.class, MemorySegment.class), FunctionDescriptor.of(JAVA_DOUBLE, D4), a), 1.0));
            row("struct argument, 24 bytes", () -> (int) down("ffi_apply_l3", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
                .invokeExact(L.upcallStub(mh1("sumL3", long.class, MemorySegment.class), FunctionDescriptor.of(JAVA_LONG, L3), a), 5L));
            row("struct result, 8 bytes", () -> (int) down("ffi_make_p2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
                .invokeExact(L.upcallStub(mh1("makeP2", MemorySegment.class, int.class), FunctionDescriptor.of(P2, JAVA_INT), a), 7));
            row("struct result, two longs", () -> (long) down("ffi_make_l2", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG))
                .invokeExact(L.upcallStub(mh1("makeL2", MemorySegment.class, long.class), FunctionDescriptor.of(L2, JAVA_LONG), a), 9L));
            row("struct result, three floats", () -> (double) down("ffi_make_f3", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_FLOAT))
                .invokeExact(L.upcallStub(mh1("makeF3", MemorySegment.class, float.class), FunctionDescriptor.of(F3, JAVA_FLOAT), a), 1.5f));
            row("struct result, four doubles", () -> (double) down("ffi_make_d4", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_DOUBLE))
                .invokeExact(L.upcallStub(mh1("makeD4", MemorySegment.class, double.class), FunctionDescriptor.of(D4, JAVA_DOUBLE), a), 2.0));
            row("struct result, char and double", () -> (double) down("ffi_make_cd", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_BYTE))
                .invokeExact(L.upcallStub(mh1("makeCD", MemorySegment.class, byte.class), FunctionDescriptor.of(CD, JAVA_BYTE), a), (byte) 64));
            row("struct result, 24 bytes", () -> (long) down("ffi_make_l3", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG))
                .invokeExact(L.upcallStub(mh1("makeL3", MemorySegment.class, long.class), FunctionDescriptor.of(L3, JAVA_LONG), a), 10L));
            row("upcalls from four threads", () -> {
                MemorySegment f = L.upcallStub(mh1("count", int.class, int.class), FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());
                MethodHandle n = down("ffi_call_n", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                ExecutorService ex = Executors.newFixedThreadPool(4);
                List<Future<Integer>> fs = new ArrayList<>();
                for (int k = 0; k < 8; k++) fs.add(ex.submit(() -> { try { return (int) n.invokeExact(f, 500); } catch (Throwable t) { throw new RuntimeException(t); } }));
                long sum = 0;
                for (Future<Integer> x : fs) sum += x.get();
                ex.shutdown();
                return sum + " " + hits.get();
            });
            row("upcalls from a C thread", () -> {
                MemorySegment f = L.upcallStub(mh1("who", int.class, int.class), FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());
                MethodHandle in = down("ffi_apply_in_thread", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
                int r1 = (int) in.invokeExact(f, 7, 1);
                int r2 = (int) in.invokeExact(f, 8, 2);
                return r1 + " " + r2 + " " + seenText + " one Thread per C thread " + (seen.get(0) != seen.get(1) && seen.get(1) == seen.get(2))
                    + ", alive once it ends " + seen.get(0).isAlive() + seen.get(2).isAlive();
            });
        }
        row("closed arena", () -> {
            Arena c = Arena.ofConfined();
            MemorySegment s = L.upcallStub(mh("twice", MethodType.methodType(int.class, int.class)), FunctionDescriptor.of(JAVA_INT, JAVA_INT), c);
            c.close();
            return s.scope().isAlive();
        });
    }
}
