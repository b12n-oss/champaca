import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

// Another thread's stack: Thread.getStackTrace on a thread other than the
// caller, and Thread.getAllStackTraces, for threads parked in each way a
// thread waits (sleep, Object.wait, LockSupport.park, a contended monitor,
// a blocking accept) under a chain of this class's own methods. Only this
// class's frames are printed, outermost last as getStackTrace orders them,
// since the JDK frames above them (Thread.sleep0, the native method on top)
// differ between VMs, as do lambda classes' own frames (HotSpot's hidden
// OtherStackCheck$$Lambda shows in getAllStackTraces); a thread not started
// or finished has none.
public class OtherStackCheck {
    static final Object LOCK = new Object();
    static final Object MONITOR = new Object();
    static volatile boolean release;

    static String own(StackTraceElement[] st) {
        List<String> names = new ArrayList<>();
        for (StackTraceElement e : st)
            if (e.getClassName().startsWith("OtherStackCheck") && !e.getClassName().contains("$$Lambda")) names.add(e.getMethodName() + ":" + e.getLineNumber());
        return names.toString();
    }
    // waits until t is in one of the states, then a little more so it is inside the call
    static void settle(Thread t, Thread.State... states) throws InterruptedException {
        Set<Thread.State> want = EnumSet.copyOf(Arrays.asList(states));
        for (int i = 0; i < 500 && !want.contains(t.getState()); i++) Thread.sleep(10);
        Thread.sleep(50);
    }

    static void sleeper() { outerSleep(); }
    static void outerSleep() { innerSleep(); }
    static void innerSleep() { try { Thread.sleep(60_000); } catch (InterruptedException e) { } }

    static void waiter() { synchronized (LOCK) { while (!release) { try { LOCK.wait(); } catch (InterruptedException e) { return; } } } }
    static void parker() { while (!release) LockSupport.park(); }
    static void blocked() { synchronized (MONITOR) { release = release; } }
    static void acceptor(ServerSocket ss) { try (Socket s = ss.accept()) { } catch (Exception e) { } }

    public static void main(String[] args) throws Exception {
        Thread s = new Thread(OtherStackCheck::sleeper, "sleeper");
        Thread w = new Thread(OtherStackCheck::waiter, "waiter");
        Thread p = new Thread(OtherStackCheck::parker, "parker");
        Thread b = new Thread(OtherStackCheck::blocked, "blocked");
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread a = new Thread(() -> acceptor(ss), "acceptor");
        Thread fresh = new Thread(() -> { }, "fresh");

        row("not started", fresh.getStackTrace().length);
        synchronized (MONITOR) {
            for (Thread t : List.of(s, w, p, b, a)) t.start();
            settle(s, Thread.State.TIMED_WAITING);
            settle(w, Thread.State.WAITING);
            settle(p, Thread.State.WAITING);
            settle(b, Thread.State.BLOCKED);
            settle(a, Thread.State.RUNNABLE);
            row("sleeping", own(s.getStackTrace()));
            row("waiting", own(w.getStackTrace()));
            row("parked", own(p.getStackTrace()));
            row("blocked on a monitor", own(b.getStackTrace()));
            row("in accept", own(a.getStackTrace()));
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            row("all stack traces", own(all.get(s)) + " " + own(all.get(w)) + " " + own(all.get(p)) + " " + own(all.get(b))
                + " " + own(all.get(a)) + " " + own(all.get(Thread.currentThread())));
            row("own stack still", own(Thread.currentThread().getStackTrace()));
            release = true;
        }
        s.interrupt();
        synchronized (LOCK) { LOCK.notifyAll(); }
        LockSupport.unpark(p);
        ss.close();
        for (Thread t : List.of(s, w, p, b, a)) t.join(10_000);
        row("finished", s.getStackTrace().length + " " + w.getStackTrace().length);
    }
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
}
