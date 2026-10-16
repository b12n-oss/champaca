// Exceptions out of synchronized methods, one level and nested: the monitor is
// released however the method ends, and another thread can take it after.
public class SyncCheck {
    static int n;
    synchronized void boom() { n++; throw new IllegalStateException("boom"); }
    synchronized int nested(int d) { if (d == 0) throw new RuntimeException("deep"); return nested(d - 1); }
    public static void main(String[] a) throws Exception {
        SyncCheck s = new SyncCheck();
        try { s.boom(); } catch (IllegalStateException e) { System.out.println("caught " + e.getMessage() + " held " + Thread.holdsLock(s)); }
        try { s.nested(5); } catch (RuntimeException e) { System.out.println("caught " + e.getMessage() + " held " + Thread.holdsLock(s)); }
        Thread t = new Thread(() -> { synchronized (s) { n += 10; } });
        t.start(); t.join(2000);
        System.out.println("other thread got the lock " + !t.isAlive() + " n=" + n);
        synchronized (s) { System.out.println("held inside " + Thread.holdsLock(s)); }
    }
}
