import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import javax.management.*;
import javax.management.openmbean.CompositeData;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

// GC notifications and GcInfo: a listener on every collector hears
// System.gc() as the JDK's own GarbageCollectionNotificationInfo (action,
// cause, a GcInfo whose usage maps name the pools), on a daemon thread named
// Notification Thread, and a collection an allocation asks for, while main
// stays busy, as a minor one; getLastGcInfo answers after a collection.
// Collector names, pool names and GC causes other than System.gc() differ
// between VMs and aren't printed.
public class GcNotificationCheck {
    static Object sink;
    public static void main(String[] a) throws Exception {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        BlockingQueue<GarbageCollectionNotificationInfo> q = new LinkedBlockingQueue<>();
        BlockingQueue<String> threads = new LinkedBlockingQueue<>();
        NotificationListener l = (n, hb) -> {
            if (n.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                q.add(GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData()));
                Thread t = Thread.currentThread();
                threads.add(t.getName() + " daemon=" + t.isDaemon());
            }
        };
        for (GarbageCollectorMXBean g : gcs) ((NotificationEmitter) g).addNotificationListener(l, null, null);
        System.gc();
        GarbageCollectionNotificationInfo info = null;
        long until = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < until) {
            GarbageCollectionNotificationInfo i = q.poll(100, TimeUnit.MILLISECONDS);
            if (i != null && i.getGcCause().equals("System.gc()")) { info = i; break; }
        }
        System.out.println("notified: " + (info != null));
        if (info != null) {
            Set<String> pools = new TreeSet<>();
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) pools.add(p.getName());
            GcInfo gi = info.getGcInfo();
            System.out.println("action: " + info.getGcAction() + ", cause: " + info.getGcCause());
            String nm = info.getGcName(); System.out.println("name is a collector: " + gcs.stream().anyMatch(g -> g.getName().equals(nm)));
            System.out.println("info: id>0 " + (gi.getId() > 0) + " end>=start " + (gi.getEndTime() >= gi.getStartTime()) + " duration>=0 " + (gi.getDuration() >= 0));
            System.out.println("usage keys are pools: " + pools.containsAll(gi.getMemoryUsageBeforeGc().keySet()) + " " + gi.getMemoryUsageAfterGc().keySet().equals(gi.getMemoryUsageBeforeGc().keySet()));
            System.out.println("thread: " + threads.poll());
        }
        java.util.concurrent.atomic.AtomicInteger minor = new java.util.concurrent.atomic.AtomicInteger();
        NotificationListener ml = (n, hb) -> {
            GarbageCollectionNotificationInfo i = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
            if (i.getGcAction().equals("end of minor GC")) minor.incrementAndGet();
        };
        for (GarbageCollectorMXBean g : gcs) ((NotificationEmitter) g).addNotificationListener(ml, null, null);
        long busy = System.nanoTime() + 20_000_000_000L;
        while (minor.get() == 0 && System.nanoTime() < busy) for (int i = 0; i < 100000; i++) sink = new byte[64];
        System.out.println("minor notified while busy: " + (minor.get() > 0));
        boolean last = gcs.stream().anyMatch(g -> ((com.sun.management.GarbageCollectorMXBean) g).getLastGcInfo() != null);
        System.out.println("last gc info: " + last);
        for (GarbageCollectorMXBean g : gcs) ((NotificationEmitter) g).removeNotificationListener(l);
        System.out.println("removed");
    }
}
