import java.lang.management.*;
import java.util.*;

// Natives a library reaches for early: the environment, process ids, the
// JDK's own native libraries, and a native that isn't there, which throws
// UnsatisfiedLinkError as on the JVM. Every row prints the same on macOS
// and Linux. bb test sets CHAMPACA_TEST_VAR=a=b=c, and so did the JVM run
// that recorded the expected output.
public class NativesCheck {
    static native int noLibrary();

    static String fails(Runnable r) {
        try { r.run(); return "no exception"; } catch (Throwable t) { return t.getClass().getName(); }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        System.out.println("env: " + (env.size() > 3) + " " + env.get("CHAMPACA_TEST_VAR") + " " + System.getenv("CHAMPACA_TEST_VAR")
                           + " " + env.containsKey("PATH") + " " + (System.getenv("NO_SUCH_VAR_CHAMPACA") == null)
                           + " " + env.get("PATH").equals(System.getenv("PATH")));
        System.out.println("env is read-only: " + fails(() -> env.put("x", "y")));
        ProcessHandle me = ProcessHandle.current();
        System.out.println("pid: " + (me.pid() > 0) + " " + me.isAlive() + " " + me.parent().isPresent()
                           + " " + ProcessHandle.of(me.pid()).isPresent() + " " + ProcessHandle.of(Integer.MAX_VALUE - 7).isPresent());
        String lib = System.mapLibraryName("champaca");
        System.out.println("mapLibraryName: " + lib.startsWith("libchampaca.") + " " + (lib.endsWith(".so") || lib.endsWith(".dylib")));
        System.out.println("no such library: " + fails(() -> System.loadLibrary("nosuchlib_champaca")));
        System.out.println("missing native: " + fails(() -> noLibrary()));
        Thread t = new Thread(() -> noLibrary(), "native-less");
        t.setUncaughtExceptionHandler((th, e) -> System.out.println("in thread " + th.getName() + ": " + e.getClass().getName()));
        t.start();
        t.join();
        // the JDK's management library loads as a no-op, and the beans answer
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        System.out.println("runtime bean: " + (rt.getUptime() >= 0) + " " + (rt.getStartTime() > 0) + " " + rt.getSpecName());
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        System.out.println("heap: " + (heap.getUsed() >= 0) + " " + (heap.getCommitted() >= heap.getUsed())
                           + " " + (heap.getMax() == -1 || heap.getMax() >= heap.getCommitted()));
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        System.out.println("os bean: " + (os.getAvailableProcessors() >= 1) + " " + os.getName().isEmpty() + " " + (os.getSystemLoadAverage() >= 0));
        System.out.println("threads: " + (ManagementFactory.getThreadMXBean().getThreadCount() >= 1));
    }
}
