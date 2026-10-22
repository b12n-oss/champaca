import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

// Stack traces: getStackTrace, printStackTrace, Thread.getStackTrace,
// StackWalker and the logging source location, with file names and line
// numbers as the JVM reports them. JDK frames print without line numbers:
// a JDK patch release may move them, and champaca gives none for them.
public class StackCheck {
    static int c() { throw new IllegalStateException("from c"); }
    static int b() { return c() + 1; }
    static int a() { return b() + 1; }

    static class Custom extends RuntimeException {
        Custom(String m) { super(m); }
        Custom() { this("chained"); }
    }
    static void throwsCustom() { throw new Custom(); }
    // a throwable made inside another throwable's constructor keeps that frame
    static class Wrapper extends RuntimeException { final Throwable made; Wrapper() { made = new IllegalArgumentException(); } }
    // synchronized methods, and leaves whose only call is a throwing helper
    static synchronized void syncStatic() { throw new IllegalStateException("sync"); }
    synchronized void syncRec(int n) { if (n == 0) throw new IllegalStateException("sync n"); syncRec(n - 1); }
    static int div(int a, int b) { return a / b; }
    static long rem(long a, long b) { return a % b; }
    static int idx(int[] a, int i) { return a[i]; }
    static String cast(Object o) { return (String) o; }
    static int[] arr(int n) { return new int[n]; }
    static void store(Object[] a) { a[0] = 1; }
    static int len(String s) { return s.length(); }
    static class BadInit { static int x = 1 / Integer.parseInt("0"); }

    static String frames(StackTraceElement[] st, Predicate<StackTraceElement> keep) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : st) {
            if (!keep.test(e)) continue;
            if (sb.length() > 0) sb.append(" < ");
            sb.append(e.getClassName().startsWith("StackCheck")
                      ? e.toString()
                      : e.getClassName() + "." + e.getMethodName());
        }
        return sb.toString();
    }
    static String ours(StackTraceElement[] st) { return frames(st, e -> e.getClassName().startsWith("StackCheck")); }
    static String all(StackTraceElement[] st) { return frames(st, e -> true); }

    static Class<?> caller() { return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass(); }
    static class Other { static Class<?> askCaller() { return caller(); } }

    static List<String> walked() {
        return StackWalker.getInstance().walk(s -> s.filter(f -> f.getClassName().startsWith("StackCheck"))
                                                   .map(f -> f.getMethodName() + ":" + f.getLineNumber() + ":" + f.getFileName())
                                                   .toList());
    }

    public static void main(String[] args) throws Exception {
        // the top frame is this line
        System.out.println("here: " + new Throwable().getStackTrace()[0]);
        try { a(); }
        catch (IllegalStateException e) {
            System.out.println("chain: " + all(e.getStackTrace()));
            StringWriter w = new StringWriter();
            e.printStackTrace(new PrintWriter(w));
            System.out.print("printed:\n" + w);
        }
        // the exception's own constructors are left out, as on the JVM
        try { throwsCustom(); } catch (Custom e) { System.out.println("custom: " + all(e.getStackTrace())); }
        // the frame of a synchronized method, and recursion through one
        try { syncStatic(); } catch (IllegalStateException e) { System.out.println("sync static: " + ours(e.getStackTrace())); }
        try { new StackCheck().syncRec(2); } catch (IllegalStateException e) { System.out.println("sync recursion: " + ours(e.getStackTrace())); }
        // exceptions the runtime throws itself, from small leaf methods
        try { div(1, 0); } catch (ArithmeticException e) { System.out.println("div: " + ours(e.getStackTrace())); }
        try { rem(1L, 0L); } catch (ArithmeticException e) { System.out.println("rem: " + ours(e.getStackTrace())); }
        try { idx(new int[1], 5); } catch (ArrayIndexOutOfBoundsException e) { System.out.println("index: " + ours(e.getStackTrace())); }
        try { cast(42); } catch (ClassCastException e) { System.out.println("cast: " + ours(e.getStackTrace())); }
        try { arr(-1); } catch (NegativeArraySizeException e) { System.out.println("size: " + ours(e.getStackTrace())); }
        try { store(new String[1]); } catch (ArrayStoreException e) { System.out.println("store: " + ours(e.getStackTrace())); }
        try { len(null); } catch (NullPointerException e) { System.out.println("null: " + ours(e.getStackTrace())); }
        try { System.out.println(BadInit.x); }
        catch (ExceptionInInitializerError e) { System.out.println("init: " + ours(e.getStackTrace()) + " cause " + ours(e.getCause().getStackTrace())); }
        System.out.println("constructor kept: " + ours(new Wrapper().made.getStackTrace()));
        // a JDK method throwing: JDK frames by name, ours with lines
        try { Integer.parseInt("x"); } catch (NumberFormatException e) {
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println("jdk: " + all(e.getStackTrace()) + " / " + top.getModuleName() + " " + top.getFileName());
        }
        // inside a lambda and a stream
        try { List.of(1).forEach(x -> { throw new UnsupportedOperationException("in lambda"); }); }
        catch (UnsupportedOperationException e) { System.out.println("lambda: " + ours(e.getStackTrace())); }
        // inside try, catch, synchronized and a loop
        try { try { c(); } catch (IllegalStateException e) { throw new IllegalArgumentException(e); } }
        catch (IllegalArgumentException e) { System.out.println("in catch: " + ours(e.getStackTrace()) + " cause " + ours(e.getCause().getStackTrace())); }
        synchronized (StackCheck.class) {
            try { b(); } catch (IllegalStateException e) { System.out.println("in synchronized: " + ours(e.getStackTrace())); }
        }
        for (int i = 0; i < 2; i++) {
            if (i == 1) { try { c(); } catch (IllegalStateException e) { System.out.println("in loop: " + ours(e.getStackTrace())); } }
        }
        // a caught exception rethrown keeps its trace; fillInStackTrace moves it
        try { try { c(); } catch (IllegalStateException e) { throw e; } }
        catch (IllegalStateException e) { System.out.println("rethrown: " + ours(e.getStackTrace())); e.fillInStackTrace(); System.out.println("refilled: " + ours(e.getStackTrace())); }
        // setStackTrace wins over the recorded one
        Throwable t = new Throwable();
        t.setStackTrace(new StackTraceElement[] { new StackTraceElement("X", "y", "X.java", 7) });
        System.out.println("set: " + t.getStackTrace()[0] + " " + t.getStackTrace().length);
        // an exception made in another thread
        Throwable[] made = new Throwable[1];
        Thread th = new Thread(() -> made[0] = new Throwable(), "maker");
        th.start(); th.join();
        System.out.println("thread: " + ours(made[0].getStackTrace()) + " / " + frames(made[0].getStackTrace(), e -> !e.getClassName().startsWith("StackCheck")));
        // Thread.getStackTrace, StackWalker, the caller class, logging
        System.out.println("current thread: " + ours(Thread.currentThread().getStackTrace()));
        System.out.println("caller: " + Other.askCaller().getSimpleName() + " " + caller().getSimpleName());
        System.out.println("walked: " + walked());
        Logger lg = Logger.getLogger("stackcheck"); lg.setUseParentHandlers(false);
        String[] src = new String[1];
        lg.addHandler(new Handler() {
            public void publish(LogRecord r) { src[0] = r.getSourceClassName() + "." + r.getSourceMethodName(); }
            public void flush() {} public void close() {} });
        lg.info("where");
        System.out.println("logged from: " + src[0]);
        // deep recursion: every activation is a frame, and a StackWalker gets
        // them in several batches
        try { depth(300); } catch (IllegalStateException e) {
            int n = 0; for (StackTraceElement f : e.getStackTrace()) if (f.getMethodName().equals("depth")) n++;
            System.out.println("recursion frames: " + n);
        }
        System.out.println("walked deep: " + walkDeep(300));
        // a trace stops at 1024 frames
        try { depth(1500); } catch (IllegalStateException e) { System.out.println("cap: " + e.getStackTrace().length); }
    }
    static int depth(int n) { if (n == 0) throw new IllegalStateException(); return depth(n - 1) + 1; }
    static long walkDeep(int n) {
        if (n == 0) return StackWalker.getInstance().walk(s -> s.filter(f -> f.getMethodName().equals("walkDeep")).count());
        return walkDeep(n - 1);
    }
}
