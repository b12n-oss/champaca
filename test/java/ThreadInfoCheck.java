import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

// ThreadMXBean's ThreadInfo, thread dumps and deadlock detection: each
// thread's state, the lock it blocks or waits on and that lock's owner, its
// blocked and waited counts, the monitors and ownable synchronizers it holds
// and the top of its stack. Only what doesn't depend on timing is printed:
// counts only for this thread's own sleeps, waits and parks, frames as
// class.method with a native flag (module prefixes differ), monitors by
// class (the frame depth a monitor was locked at is -1 on champaca).
public class ThreadInfoCheck {
    static final ThreadMXBean TB = ManagementFactory.getThreadMXBean();

    static String frame(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName() + (e.isNativeMethod() ? " native" : "");
    }
    static String lock(ThreadInfo ti) {
        return ti.getLockName() == null ? "none" : ti.getLockName().replaceAll("@[0-9a-f]+$", "");
    }
    static void show(String what, Thread t, boolean counts) {
        ThreadInfo ti = TB.getThreadInfo(new long[]{t.threadId()}, true, true)[0];
        StackTraceElement[] st = ti.getStackTrace();
        System.out.println(what + ": " + ti.getThreadName() + " " + ti.getThreadState() + " lock=" + lock(ti)
            + " owner=" + ti.getLockOwnerName() + (counts ? " blocked=" + ti.getBlockedCount() + " waited=" + ti.getWaitedCount() : "")
            + " monitors=" + Arrays.stream(ti.getLockedMonitors()).map(MonitorInfo::getClassName).toList()
            + " syncs=" + Arrays.stream(ti.getLockedSynchronizers()).map(LockInfo::getClassName).toList()
            + " top=" + (st.length > 0 ? frame(st[0]) : "none"));
    }
    static void waitFor(Thread t, Thread.State s) throws Exception { while (t.getState() != s) Thread.sleep(2); }
    static List<String> names(long[] ids) {
        if (ids == null) return null;
        return Arrays.stream(ids).mapToObj(i -> TB.getThreadInfo(i).getThreadName()).sorted().toList();
    }

    public static void main(String[] a) throws Exception {
        Thread me = Thread.currentThread();
        System.out.println("supports: monitors " + TB.isObjectMonitorUsageSupported() + " synchronizers " + TB.isSynchronizerUsageSupported()
            + " contention " + TB.isThreadContentionMonitoringSupported() + " enabled " + TB.isThreadContentionMonitoringEnabled());
        show("self", me, true);
        Thread.sleep(1); Thread.sleep(1);
        show("after two sleeps", me, true);
        Object o = new Object();
        synchronized (o) { o.wait(1); }
        show("after a wait", me, true);
        LockSupport.parkNanos(1000);
        show("after a park", me, true);
        synchronized (o) { synchronized ("x") { show("holding two monitors", me, true); } }
        ReentrantLock rl = new ReentrantLock();
        rl.lock(); show("holding a ReentrantLock", me, true); rl.unlock();
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock(); show("holding a write lock", me, false); rw.writeLock().unlock();
        show("released", me, false);

        Object w = new Object();
        Thread waiter = new Thread(() -> { synchronized (w) { try { w.wait(); } catch (InterruptedException e) {} } }, "waiter");
        waiter.setDaemon(true); waiter.start(); waitFor(waiter, Thread.State.WAITING);
        show("waiting", waiter, true);
        synchronized (w) { show("waiting, its monitor held", waiter, true); }
        Thread sleeper = new Thread(() -> { try { Thread.sleep(100000); } catch (InterruptedException e) {} }, "sleeper");
        sleeper.setDaemon(true); sleeper.start(); waitFor(sleeper, Thread.State.TIMED_WAITING);
        show("sleeping", sleeper, true);
        rl.lock();
        Thread parked = new Thread(() -> { rl.lock(); rl.unlock(); }, "parked");
        parked.setDaemon(true); parked.start(); waitFor(parked, Thread.State.WAITING);
        show("parked on a lock main holds", parked, true);
        rl.unlock(); parked.join();
        Object m = new Object();
        Thread blocked;
        synchronized (m) {
            blocked = new Thread(() -> { synchronized (m) {} }, "blocked");
            blocked.setDaemon(true); blocked.start(); waitFor(blocked, Thread.State.BLOCKED);
            show("blocked on main's monitor", blocked, true);
        }
        blocked.join();
        System.out.println("no deadlocks: " + names(TB.findMonitorDeadlockedThreads()) + " " + names(TB.findDeadlockedThreads()));

        Object l1 = new Object(), l2 = new Object();
        CountDownLatch both = new CountDownLatch(2);
        Thread d1 = new Thread(() -> { synchronized (l1) { both.countDown(); try { both.await(); } catch (Exception e) {} synchronized (l2) {} } }, "d1");
        Thread d2 = new Thread(() -> { synchronized (l2) { both.countDown(); try { both.await(); } catch (Exception e) {} synchronized (l1) {} } }, "d2");
        Thread d3 = new Thread(() -> { synchronized (l1) {} }, "d3");
        for (Thread t : new Thread[]{d1, d2}) { t.setDaemon(true); t.start(); }
        waitFor(d1, Thread.State.BLOCKED); waitFor(d2, Thread.State.BLOCKED);
        d3.setDaemon(true); d3.start(); waitFor(d3, Thread.State.BLOCKED);
        show("deadlocked", d1, false);
        show("behind the deadlock", d3, false);
        System.out.println("monitor deadlock: " + names(TB.findMonitorDeadlockedThreads()) + " " + names(TB.findDeadlockedThreads()));

        ReentrantLock r1 = new ReentrantLock(), r2 = new ReentrantLock();
        CountDownLatch both2 = new CountDownLatch(2);
        Thread e1 = new Thread(() -> { r1.lock(); both2.countDown(); try { both2.await(); } catch (Exception e) {} r2.lock(); }, "e1");
        Thread e2 = new Thread(() -> { r2.lock(); both2.countDown(); try { both2.await(); } catch (Exception e) {} r1.lock(); }, "e2");
        for (Thread t : new Thread[]{e1, e2}) { t.setDaemon(true); t.start(); }
        while (!(r1.hasQueuedThread(e2) && r2.hasQueuedThread(e1))) Thread.sleep(2);
        show("lock deadlocked", e1, false);
        System.out.println("lock deadlock: " + names(TB.findMonitorDeadlockedThreads()) + " " + names(TB.findDeadlockedThreads()));

        ThreadInfo[] all = TB.dumpAllThreads(true, true);
        System.out.println("dump: " + Arrays.stream(all).map(ThreadInfo::getThreadName).filter(n -> n.matches("d\\d|e\\d|main|waiter|sleeper")).sorted().toList());
        ThreadInfo selfDump = Arrays.stream(all).filter(t -> t.getThreadId() == me.threadId()).findFirst().get();
        System.out.println("dump's own top: " + frame(selfDump.getStackTrace()[0]) + ", then " + frame(selfDump.getStackTrace()[1]));
        System.out.println("dump without stacks: " + Arrays.stream(TB.dumpAllThreads(false, false, 0)).allMatch(t -> t.getStackTrace().length == 0));
        System.out.println("depth 0: " + TB.getThreadInfo(me.threadId(), 0).getStackTrace().length + ", depth 2: " + TB.getThreadInfo(me.threadId(), 2).getStackTrace().length);
        System.out.println("info top: " + frame(TB.getThreadInfo(me.threadId(), 1).getStackTrace()[0]));
        System.out.println("other thread's stack: " + frame(sleeper.getStackTrace()[0]) + ", " + frame(waiter.getStackTrace()[0]));
        System.out.println("unknown id: " + TB.getThreadInfo(123456789L) + " " + Arrays.toString(TB.getThreadInfo(new long[]{123456789L})));
        try { TB.getThreadInfo(0L); } catch (Exception e) { System.out.println("id 0: " + e); }
        System.out.println("times off: " + TB.getThreadInfo(me.threadId()).getBlockedTime() + " " + TB.getThreadInfo(me.threadId()).getWaitedTime());
        TB.setThreadContentionMonitoringEnabled(true);
        Thread.sleep(20);
        ThreadInfo timed = TB.getThreadInfo(me.threadId());
        System.out.println("times on: enabled " + TB.isThreadContentionMonitoringEnabled() + " waited >= 0 " + (timed.getWaitedTime() >= 0) + " blocked >= 0 " + (timed.getBlockedTime() >= 0));
        TB.resetPeakThreadCount();
        System.out.println("peak after reset: " + (TB.getPeakThreadCount() == TB.getThreadCount()));
        System.out.println("toString: " + TB.getThreadInfo(sleeper.threadId()).toString().split("\n")[0].replaceAll("Id=\\d+", "Id=N"));
    }
}
