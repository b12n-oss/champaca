import java.lang.management.*;
import java.util.*;
// The memory pool and garbage collector beans: what holds whatever the VM's
// collectors and pools are (HotSpot has G1's three pools and two
// collectors, champaca one of each): a collection is counted, each
// collector's pools exist, the beans are jdk.management's notification
// emitters, usage figures are consistent, and the MBean server lists them.
public class MemoryBeanCheck {
    public static void main(String[] a) throws Exception {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        System.out.println("gcs>0 " + !gcs.isEmpty() + " pools>0 " + !pools.isEmpty());
        long before = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        System.gc();
        long after = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        System.out.println("gc counted " + (after > before) + " time>=0 " + gcs.stream().allMatch(g -> g.getCollectionTime() >= 0));
        System.out.println("gc pools exist " + gcs.stream().allMatch(g -> Arrays.stream(g.getMemoryPoolNames()).allMatch(n -> pools.stream().anyMatch(p -> p.getName().equals(n)))));
        System.out.println("gc valid " + gcs.stream().allMatch(MemoryManagerMXBean::isValid));
        System.out.println("gc emitter " + gcs.stream().allMatch(g -> g instanceof javax.management.NotificationEmitter));
        System.out.println("gc ext " + gcs.stream().allMatch(g -> g instanceof com.sun.management.GarbageCollectorMXBean));
        System.out.println("heap pool " + pools.stream().anyMatch(p -> p.getType() == MemoryType.HEAP));
        System.out.println("usage sane " + pools.stream().allMatch(p -> { MemoryUsage u = p.getUsage(); return u.getUsed() <= u.getCommitted() && (u.getMax() < 0 || u.getCommitted() <= u.getMax()); }));
        System.out.println("peak sane " + pools.stream().allMatch(p -> p.getPeakUsage() != null));
        System.out.println("pool managers " + pools.stream().allMatch(p -> p.getMemoryManagerNames().length > 0));
        System.out.println("reset peak " + pools.stream().allMatch(p -> { p.resetPeakUsage(); return true; }));
        System.out.println("names unique " + (pools.stream().map(MemoryPoolMXBean::getName).distinct().count() == pools.size()));
        for (MemoryPoolMXBean p : pools) {
            try { p.getUsageThreshold(); } catch (UnsupportedOperationException e) { System.out.println("threshold unsupported somewhere"); break; }
        }
        System.out.println("object names " + gcs.stream().allMatch(g -> g.getObjectName().toString().startsWith("java.lang:type=GarbageCollector,name=")));
        System.out.println("server " + (java.lang.management.ManagementFactory.getPlatformMBeanServer().queryNames(new javax.management.ObjectName("java.lang:type=MemoryPool,*"), null).size() == pools.size()));
        System.out.println("platform gc beans " + (ManagementFactory.getPlatformMXBeans(com.sun.management.GarbageCollectorMXBean.class).size() == gcs.size()));
        System.out.println("buffer pools " + ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream().map(BufferPoolMXBean::getName).sorted().toList());
    }
}
