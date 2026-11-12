import java.lang.management.*;
import javax.management.*;

// ManagementFactory's CompilationMXBean: there whenever the VM has a compiler
// (HotSpot without -Xint, champaca always), named by sun.management.compiler,
// with compilation time monitoring and a total that only grows. The name
// itself differs by VM, so only properties that hold whatever it is print.
public class CompilationBeanCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static long work(int n) { long s = 0; for (int i = 0; i < n; i++) s += Integer.toString(i).hashCode(); return s; }

    public static void main(String[] args) throws Exception {
        CompilationMXBean c = ManagementFactory.getCompilationMXBean();
        row("bean", c != null);
        row("name", c.getName() != null && !c.getName().isEmpty());
        row("name is the property", c.getName().equals(System.getProperty("sun.management.compiler")));
        row("time monitoring", c.isCompilationTimeMonitoringSupported());
        long t1 = c.getTotalCompilationTime();
        work(200000);
        java.util.stream.IntStream.range(0, 1000).mapToObj(i -> "x" + i).sorted().count();
        long t2 = c.getTotalCompilationTime();
        row("total", (t1 >= 0) + " " + (t2 >= t1));
        row("object name", c.getObjectName());
        row("platform bean", ManagementFactory.getPlatformMXBean(CompilationMXBean.class) == c);
        MBeanServer s = ManagementFactory.getPlatformMBeanServer();
        ObjectName on = new ObjectName(ManagementFactory.COMPILATION_MXBEAN_NAME);
        row("registered", s.isRegistered(on));
        row("jmx name", c.getName().equals(s.getAttribute(on, "Name")));
        row("jmx time", ((Long) s.getAttribute(on, "TotalCompilationTime")) >= t2);
        row("jmx monitoring", s.getAttribute(on, "CompilationTimeMonitoringSupported"));
        row("in the list", ManagementFactory.getPlatformMXBeans(CompilationMXBean.class).size());
    }
}
