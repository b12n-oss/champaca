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
        // a pid kill() would read as a process group, and one beyond int range
        // (HotSpot truncates it; the point here is that nothing crashes)
        System.out.println("odd pids: " + ProcessHandle.of(-1).isPresent() + " " + fails(() -> ProcessHandle.of(1L << 40)));
        // the parent of another process, here of our parent (absent only under an init process)
        ProcessHandle parent = me.parent().get();
        System.out.println("grandparent: " + (parent.parent().isPresent() == (parent.pid() != 1)));
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
        int before = ManagementFactory.getThreadMXBean().getThreadCount();
        System.out.println("threads: " + (before >= 1));
        // onExit on a process that isn't our child: a reaper thread polls it,
        // and a failure in that thread would reach the default handler
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> System.out.println("uncaught in " + th.getName() + ": " + e.getClass().getName()));
        java.util.concurrent.CompletableFuture<ProcessHandle> exit = ProcessHandle.of(1).get().onExit();
        Thread.sleep(200);
        System.out.println("onExit polls: " + !exit.isDone());
        Thread.setDefaultUncaughtExceptionHandler(null);
        // Console.istty (stdin and stdout both terminals) decides what
        // System.console() gives; bb test pipes both
        java.io.Console con = System.console();
        System.out.println("console: " + (con == null ? "none" : "terminal " + con.isTerminal()));
        // a class whose initializer failed: the second use names the first failure
        for (int i = 0; i < 2; i++) {
            try { System.out.println(BadInit.x); }
            catch (Throwable err) { System.out.println("bad init " + i + ": " + err.getClass().getName() + " / " + err.getCause()); }
        }
    }
    static class BadInit { static int x = 1 / Integer.parseInt("0"); }
}
