import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

// Monitors on objects, arrays and class mirrors; per-site instanceof,
// checkcast and class-initialization caches; Unsafe atomics on fields.
public class MonitorCheck {
    static int counter;
    static synchronized void staticSync() { counter++; }
    synchronized int instSync(int x) { return x + 1; }

    static String monitors() throws Exception {
        int[] arr = new int[1];
        Object o = new Object();
        StringBuilder sb = new StringBuilder();
        synchronized (arr) { arr[0]++; sb.append(Thread.holdsLock(arr)); }
        synchronized (MonitorCheck.class) { sb.append(' ').append(Thread.holdsLock(MonitorCheck.class)); }
        staticSync();
        sb.append(' ').append(Thread.holdsLock(MonitorCheck.class));
        synchronized (o) { synchronized (o) { sb.append(' ').append(Thread.holdsLock(o)); } sb.append(' ').append(Thread.holdsLock(o)); }
        sb.append(' ').append(Thread.holdsLock(o)).append(' ').append(Thread.holdsLock(arr));
        // wait and notify between two threads, on an object and on an array
        for (Object lock : new Object[] { new Object(), new long[2] }) {
            boolean[] ready = new boolean[1];
            Thread t = new Thread(() -> { synchronized (lock) { ready[0] = true; lock.notifyAll(); } });
            synchronized (lock) {
                t.start();
                while (!ready[0]) lock.wait();
            }
            t.join();
            sb.append(" woke");
        }
        // two threads contending for one monitor
        int[] shared = new int[1];
        Object lock = new Object();
        Runnable bump = () -> { for (int i = 0; i < 20000; i++) synchronized (lock) { shared[0]++; } };
        Thread a = new Thread(bump), b = new Thread(bump);
        a.start(); b.start(); a.join(); b.join();
        sb.append(' ').append(shared[0]);
        MonitorCheck m = new MonitorCheck();
        int s = 0;
        for (int i = 0; i < 1000; i++) s = m.instSync(s);
        sb.append(' ').append(s).append(' ').append(counter);
        try {
            o.notify();
            sb.append(" no throw");
        } catch (IllegalMonitorStateException e) {
            sb.append(" IMSE");
        }
        return sb.toString();
    }

    interface Shape { }
    static class Sq implements Shape { }
    static class Circle implements Shape { }
    static class Big extends Sq { }

    static String kind(Object x) {
        if (x instanceof Big) return "big";
        if (x instanceof Sq) return "sq";
        if (x instanceof Shape) return "shape";
        if (x instanceof int[]) return "ints";
        if (x instanceof Object[]) return "objs";
        return x == null ? "null" : "other";
    }

    static Shape asShape(Object x) { return (Shape) x; }

    static String casts() {
        List<String> out = new ArrayList<>();
        Object[] xs = { new Sq(), new Big(), new Circle(), "s", new int[0], new String[0], null, new Sq(), new Big() };
        for (Object x : xs) out.add(kind(x));
        // a site that caches a passing class must still fail for another one
        int ok = 0;
        Object[] ys = { new Sq(), new Sq(), new Circle(), "not a shape", "again", new Big(), null, Integer.valueOf(1) };
        for (Object y : ys) {
            try { asShape(y); ok++; } catch (ClassCastException e) { out.add("cce"); }
        }
        out.add("ok " + ok);
        return out.toString();
    }

    static class Boom {
        static int value = init();
        static int init() { throw new IllegalStateException("clinit"); }
    }
    static class Lazy {
        static int inits;
        static int value;
        static { inits++; value = 42; }
        static int get() { return value; }
    }

    static String inits() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            try {
                sb.append(Boom.value);
            } catch (ExceptionInInitializerError e) {
                sb.append("EIIE ");
            } catch (NoClassDefFoundError e) {
                sb.append("NCDFE ");
            }
        }
        int s = 0;
        for (int i = 0; i < 5; i++) s += Lazy.get() + Lazy.value;
        sb.append(s).append(" inits ").append(Lazy.inits);
        return sb.toString();
    }

    volatile int vint;
    static final AtomicIntegerFieldUpdater<MonitorCheck> VINT =
        AtomicIntegerFieldUpdater.newUpdater(MonitorCheck.class, "vint");

    static String atomics() {
        AtomicInteger ai = new AtomicInteger();
        AtomicLong al = new AtomicLong(Long.MAX_VALUE - 5);
        AtomicReference<String> ar = new AtomicReference<>("a");
        AtomicReferenceArray<String> ara = new AtomicReferenceArray<>(3);
        for (int i = 0; i < 1000; i++) ai.incrementAndGet();
        for (int i = 0; i < 10; i++) al.incrementAndGet();
        boolean c1 = ar.compareAndSet("a", "b"), c2 = ar.compareAndSet("a", "c");
        ara.set(1, "x");
        boolean c3 = ara.compareAndSet(1, "x", "y");
        MonitorCheck m = new MonitorCheck();
        for (int i = 0; i < 7; i++) VINT.incrementAndGet(m);
        boolean c4 = VINT.compareAndSet(m, 7, 70), c5 = VINT.compareAndSet(m, 7, 71);
        ReentrantLock lock = new ReentrantLock();
        lock.lock(); lock.lock();
        int holds = lock.getHoldCount();
        lock.unlock(); lock.unlock();
        return ai.get() + " " + al.get() + " " + ar.get() + " " + c1 + c2 + " " + ara.get(1) + c3
            + " " + m.vint + " " + c4 + c5 + " holds " + holds + " " + lock.isLocked();
    }

    // new of a class whose initializer failed, casts of arrays, a clone of
    // a locked object, and a static field through a VarHandle
    static class BadNew {
        static int v = init();
        static int init() { throw new IllegalStateException("clinit"); }
    }
    static class Box implements Cloneable {
        int v = 1;
        public Box clone() { try { return (Box) super.clone(); } catch (CloneNotSupportedException e) { throw new RuntimeException(e); } }
    }
    static volatile int staticCounter;
    static final java.lang.invoke.VarHandle STATIC_COUNTER;
    static {
        try {
            STATIC_COUNTER = java.lang.invoke.MethodHandles.lookup().findStaticVarHandle(MonitorCheck.class, "staticCounter", int.class);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object[] asObjects(Object x) { return (Object[]) x; }
    static Cloneable asCloneable(Object x) { return (Cloneable) x; }

    static String more() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            try { new BadNew(); sb.append("made "); }
            catch (ExceptionInInitializerError e) { sb.append("EIIE "); }
            catch (NoClassDefFoundError e) { sb.append("NCDFE "); }
        }
        Object[] xs = { new String[1], new int[1], new Object[0], "s", new Integer[2][2] };
        for (Object x : xs) {
            try { asObjects(x); sb.append("o"); } catch (ClassCastException e) { sb.append("x"); }
            try { asCloneable(x); sb.append("c "); } catch (ClassCastException e) { sb.append("- "); }
        }
        Box b = new Box();
        synchronized (b) {
            Box c = b.clone();
            sb.append(Thread.holdsLock(b)).append(' ').append(Thread.holdsLock(c)).append(' ').append(c.v);
            synchronized (c) { sb.append(' ').append(Thread.holdsLock(c)); }
        }
        for (int i = 0; i < 5; i++) STATIC_COUNTER.getAndAdd(2);
        boolean ok = STATIC_COUNTER.compareAndSet(10, 11), no = STATIC_COUNTER.compareAndSet(10, 12);
        sb.append(' ').append(staticCounter).append(' ').append(ok).append(no);
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        System.out.println(monitors());
        System.out.println(casts());
        System.out.println(inits());
        System.out.println(atomics());
        System.out.println(more());
    }
}
