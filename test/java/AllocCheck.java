import java.lang.management.ManagementFactory;

// ThreadMXBean's allocated-bytes counters: each row a fact that holds on
// any machine (a range, never a byte count)
public class AllocCheck {
    static com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static Object sink;
    static void row(String name, boolean ok) { System.out.println(name + ": " + ok); }

    public static void main(String[] a) throws Exception {
        row("supported", mx.isThreadAllocatedMemorySupported());
        row("enabled by default", mx.isThreadAllocatedMemoryEnabled());
        long b0 = mx.getCurrentThreadAllocatedBytes();
        Object[] keep = new Object[1000];
        for (int i = 0; i < 1000; i++) keep[i] = new byte[1000];
        long b1 = mx.getCurrentThreadAllocatedBytes();
        row("1 MB of arrays counted", b1 - b0 >= 1_000_000);
        // a byte[] element takes 8 bytes here (a Scheme vector), one on HotSpot
        row("and not much more", b1 - b0 < 32_000_000);
        long tid = Thread.currentThread().threadId();
        row("by id, the same thread", mx.getThreadAllocatedBytes(tid) >= b1);
        row("by array of ids", mx.getThreadAllocatedBytes(new long[] {tid})[0] >= b1);
        row("total covers the thread", mx.getTotalThreadAllocatedBytes() >= b1);
        long g0 = mx.getCurrentThreadAllocatedBytes();
        System.gc();
        row("a collection doesn't lower it", mx.getCurrentThreadAllocatedBytes() >= g0);

        long[] seen = new long[1];
        Thread t = new Thread(() -> {
            long s = mx.getCurrentThreadAllocatedBytes();
            sink = new byte[2_000_000];
            seen[0] = mx.getCurrentThreadAllocatedBytes() - s;
        });
        row("a thread not started is -1", mx.getThreadAllocatedBytes(t.threadId()) == -1);
        sink = new byte[1_000_000];
        long m0 = mx.getCurrentThreadAllocatedBytes();
        t.start();
        t.join();
        long m1 = mx.getCurrentThreadAllocatedBytes();
        row("waiting for a thread keeps what main allocated", m1 >= m0);
        // a sleep gives the GIL up and takes it back; the first one also
        // compiles or loads what it calls, so the row times the second
        Thread.sleep(1);
        sink = new byte[1_000_000];
        long s0 = mx.getCurrentThreadAllocatedBytes();
        Thread.sleep(1);
        row("a sleep keeps what main allocated", mx.getCurrentThreadAllocatedBytes() >= s0);
        row("the other thread counts its own 2 MB", seen[0] >= 2_000_000);
        row("main isn't charged for it", m1 - m0 < 1_000_000);
        row("a finished thread is -1", mx.getThreadAllocatedBytes(t.threadId()) == -1);
        row("a missing id is -1", mx.getThreadAllocatedBytes(987654321L) == -1);
        long[] both = mx.getThreadAllocatedBytes(new long[] {tid, t.threadId()});
        row("arrays mix live and dead", both[0] >= m1 && both[1] == -1);

        // loading classes is champaca's work, as metadata is HotSpot's: not counted
        String[] names = {"java.util.zip.Adler32", "java.util.zip.CRC32C", "java.util.BitSet", "java.util.StringJoiner",
            "java.text.DecimalFormat", "java.text.SimpleDateFormat", "java.util.Scanner", "java.util.Timer", "java.util.PriorityQueue"};
        long l0 = mx.getCurrentThreadAllocatedBytes();
        for (String n : names) Class.forName(n, false, null);
        row("loading classes isn't counted", mx.getCurrentThreadAllocatedBytes() - l0 < 2_000_000);

        mx.setThreadAllocatedMemoryEnabled(false);
        row("disabled", !mx.isThreadAllocatedMemoryEnabled());
        row("disabled reads -1", mx.getCurrentThreadAllocatedBytes() == -1 && mx.getThreadAllocatedBytes(tid) == -1);
        mx.setThreadAllocatedMemoryEnabled(true);
        row("enabled again", mx.isThreadAllocatedMemoryEnabled() && mx.getCurrentThreadAllocatedBytes() >= m1);
        try { mx.getThreadAllocatedBytes(0); row("id 0 refused", false); }
        catch (IllegalArgumentException e) { row("id 0 refused", true); }
    }
}
