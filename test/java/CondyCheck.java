import java.lang.invoke.*;
import java.lang.reflect.InvocationTargetException;

// Constant-pool entries resolve once (JVMS 5.4.3): every ldc of a dynamic
// constant, method handle or method type entry, and a bootstrap argument
// naming it, gives the same object from one bootstrap run, null included; a
// resolution failing with a LinkageError (a bootstrap's exception wrapped in
// BootstrapMethodError, a member that isn't there) fails again without
// rerunning anything, as a new error of the same class, message and cause; an
// Error that isn't a LinkageError isn't kept; an invokedynamic whose bootstrap
// failed fails again too, without the cause. Two threads resolving one entry
// at once both run its bootstrap and both get the same object, and a thread
// whose bootstrap fails after another's succeeded throws its own error, not
// kept (HotSpot gives the success only to a class entry). A kept
// error without a message takes the constant's name, as HotSpot's. An
// invokedynamic's bootstrap returning no CallSite, null, or a target of
// another type: BootstrapMethodError with the JDK's texts. HotSpot's texts. CondyUser comes
// from test/gen/CondyGen.java.
public class CondyCheck {
    public static class Boot {
        public static int made, nothings, failed, erred, linked, indied;
        public static Object make(MethodHandles.Lookup l, String n, Class<?> t) {
            made++;
            return new Object() { public String toString() { return "made"; } };
        }
        public static Object wrap(MethodHandles.Lookup l, String n, Class<?> t, Object inner) { return new Object[] {inner}; }
        // both threads' bootstraps run, each waiting for the other, and both get the first result kept
        public static final java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        public static final java.util.concurrent.atomic.AtomicInteger raced = new java.util.concurrent.atomic.AtomicInteger();
        public static Object race(MethodHandles.Lookup l, String n, Class<?> t) throws Exception {
            raced.incrementAndGet();
            both.countDown();
            both.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return new Object();
        }
        // the first thread's bootstrap fails after the second's succeeded: it gets the second's result
        public static final java.util.concurrent.CountDownLatch firstIn = new java.util.concurrent.CountDownLatch(1);
        public static final java.util.concurrent.CountDownLatch secondDone = new java.util.concurrent.CountDownLatch(1);
        public static final java.util.concurrent.atomic.AtomicInteger raceFails = new java.util.concurrent.atomic.AtomicInteger();
        public static Object raceFail(MethodHandles.Lookup l, String n, Class<?> t) throws Exception {
            if (raceFails.getAndIncrement() == 0) {
                firstIn.countDown();
                secondDone.await(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new NoClassDefFoundError("too late");
            }
            return "second thread's";
        }
        public static int nullMessages;
        public static Object nullMessage(MethodHandles.Lookup l, String n, Class<?> t) { nullMessages++; throw new LinkageError(); }
        public static Object notCallSite(MethodHandles.Lookup l, String n, MethodType t) { return "a string"; }
        public static CallSite nullCallSite(MethodHandles.Lookup l, String n, MethodType t) { return null; }
        public static CallSite wrongType(MethodHandles.Lookup l, String n, MethodType t) {
            return new ConstantCallSite(MethodHandles.constant(String.class, "x"));
        }
        public static Object nothing(MethodHandles.Lookup l, String n, Class<?> t) { nothings++; return null; }
        public static Object failOnce(MethodHandles.Lookup l, String n, Class<?> t) {
            if (failed++ == 0) throw new IllegalStateException("first");
            return "second";
        }
        public static Object errorOnce(MethodHandles.Lookup l, String n, Class<?> t) {
            if (erred++ == 0) throw new AssertionError("first");
            return "after error";
        }
        public static Object linkageOnce(MethodHandles.Lookup l, String n, Class<?> t) {
            if (linked++ == 0) throw new NoClassDefFoundError("first");
            return "after linkage";
        }
        public static CallSite indyFailOnce(MethodHandles.Lookup l, String n, MethodType t) {
            if (indied++ == 0) throw new IllegalStateException("indy first");
            return new ConstantCallSite(MethodHandles.constant(Object.class, "indy second"));
        }
    }
    static Throwable last;
    static Object get(String m) throws Exception { return Class.forName("CondyUser").getMethod(m).invoke(null); }
    static String call(String m) {
        try {
            Object r = get(m);
            return r instanceof MethodHandle ? "a MethodHandle" : String.valueOf(r);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            String s = t.getClass().getName() + ": " + t.getMessage() + (t.getCause() == null ? "" : ", cause " + t.getCause());
            if (last != null) s += ", same object " + (t == last) + ", same cause " + (t.getCause() == last.getCause());
            last = t;
            return s;
        } catch (Exception e) { return "unexpected " + e; }
    }
    static void calls(String... ms) {
        last = null;
        for (String m : ms) System.out.println(m + ": " + call(m));
    }
    public static void main(String[] args) throws Exception {
        Object ok = get("ok1");
        System.out.println("one object across methods: " + (ok == get("ok2")) + ", in one method: " + get("pair")
                           + ", as an argument: " + (((Object[]) get("outer"))[0] == ok) + ", bootstrap runs " + Boot.made);
        Object[] seen = new Object[2];
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int k = i;
            ts[i] = new Thread(() -> { try { seen[k] = get("race"); } catch (Exception e) { seen[k] = e; } });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        System.out.println("two threads at once: same object " + (seen[0] == seen[1]) + ", bootstrap runs " + Boot.raced.get()
                           + ", and after: " + (get("race") == seen[0]));
        Object[] late = new Object[1];
        Thread first = new Thread(() -> {
            try { late[0] = get("raceFail"); }
            catch (InvocationTargetException e) { late[0] = e.getCause(); }
            catch (Exception e) { late[0] = e; } });
        first.start();
        Boot.firstIn.await();
        Object second = get("raceFail");
        Boot.secondDone.countDown();
        first.join();
        System.out.println("a bootstrap failing after another thread's succeeded: " + late[0] + ", " + second
                           + ", runs " + Boot.raceFails.get());
        System.out.println("null: " + get("nul1") + " " + get("nul2") + ", bootstrap runs " + Boot.nothings);
        System.out.println("method handle same: " + (get("mh1") == get("mh2")) + ", method type same: " + (get("mt1") == get("mt2")));
        calls("fail1", "fail1", "fail2");
        System.out.println("failing bootstrap runs " + Boot.failed);
        calls("err1", "err1");
        System.out.println("erroring bootstrap runs " + Boot.erred);
        calls("lerr1", "lerr1");
        System.out.println("linkage bootstrap runs " + Boot.linked);
        calls("missm", "missm");
        calls("missv");
        calls("missf");
        calls("missc", "missc");
        calls("nullMessage", "nullMessage");
        System.out.println("null message bootstrap runs " + Boot.nullMessages);
        calls("indy", "indy");
        calls("notCallSite", "notCallSite");
        calls("nullCallSite", "nullCallSite");
        calls("wrongType", "wrongType");
        System.out.println("indy bootstrap runs " + Boot.indied);
    }
}
