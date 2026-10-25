import java.io.*;
import java.lang.invoke.MethodHandles;
import java.lang.management.*;
import java.lang.reflect.*;
import java.util.*;

// What the VM itself answers: the live threads and their CPU times, nests,
// array class modifiers, the system packages, and the properties the
// launcher and the host give (locale, temp dir, command). Every value
// printed is the same on any machine. Upstream OpenLDK's testsuite/aaa
// threadcount, threadcpu, nestmembers, syspackage and sp ask the same.
public class VmCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    class A {}
    static class B { class C {} }
    interface I {}
    private static class Priv {}
    static class Pkg {}
    protected static class Prot {}
    static class Hid {}
    static volatile boolean spin = true;

    static String nest(Class<?> c) {
        return c.getNestHost().getSimpleName() + " " + Arrays.stream(c.getNestMembers()).map(Class::getSimpleName).sorted().toList()
            + " " + c.isNestmateOf(VmCheck.class);
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        // threads: a worker that burns CPU, then waits
        Object lock = new Object();
        Thread worker = new Thread(() -> {
            long x = 0;
            while (spin) x += System.nanoTime() % 7;
            synchronized (lock) { try { lock.wait(); } catch (InterruptedException e) { } }
        }, "worker");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(300);
        spin = false;
        Thread[] all = new Thread[Thread.activeCount() + 4];
        List<Thread> listed = Arrays.asList(all).subList(0, Thread.enumerate(all));
        row("enumerate", listed.contains(Thread.currentThread()) + " " + listed.contains(worker));
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        StackTraceElement[] mine = traces.get(Thread.currentThread());
        row("getAllStackTraces", traces.containsKey(worker) + " " + mine[0].getMethodName() + " " + mine[0].isNativeMethod()
            + " " + mine[1].getMethodName() + " " + mine[2].getMethodName() + " " + mine[2].getClassName());

        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        row("cpu time support", t.isCurrentThreadCpuTimeSupported() + " " + t.isThreadCpuTimeSupported() + " " + t.isThreadCpuTimeEnabled());
        long c1 = t.getCurrentThreadCpuTime(), u1 = t.getCurrentThreadUserTime();
        long x = 0;
        for (long end = System.nanoTime() + 100_000_000L; System.nanoTime() < end; ) x += x % 7 + 1;
        long c2 = t.getCurrentThreadCpuTime(), u2 = t.getCurrentThreadUserTime();
        row("current thread cpu", (c1 > 0) + " " + (c2 > c1) + " " + (c2 - c1 > 10_000_000L) + " " + (u2 > u1) + " " + (u2 <= c2) + " " + (x > 0));
        long w = t.getThreadCpuTime(worker.threadId()), wu = t.getThreadUserTime(worker.threadId());
        row("other thread cpu", (w > 10_000_000L) + " " + (wu > 0) + " " + (wu <= w) + " " + t.getThreadCpuTime(Long.MAX_VALUE));
        long[] both = ((com.sun.management.ThreadMXBean) t).getThreadCpuTime(new long[] {worker.threadId(), Long.MAX_VALUE});
        long[] bothUser = ((com.sun.management.ThreadMXBean) t).getThreadUserTime(new long[] {worker.threadId(), Long.MAX_VALUE});
        row("thread cpu arrays", (both[0] >= w) + " " + both[1] + " " + (bothUser[0] >= wu) + " " + bothUser[1]);
        t.setThreadCpuTimeEnabled(false);
        row("cpu time disabled", t.isThreadCpuTimeEnabled() + " " + t.getCurrentThreadCpuTime() + " " + t.getThreadCpuTime(worker.threadId()));
        t.setThreadCpuTimeEnabled(true);
        row("cpu time enabled again", t.getCurrentThreadCpuTime() > c2);
        synchronized (lock) { lock.notifyAll(); }

        // nests
        for (Class<?> c : new Class<?>[] { VmCheck.class, A.class, B.C.class, I.class, int.class, int[].class, Map.Entry.class })
            row("nest " + c.getSimpleName(), nest(c));
        row("nest host first", VmCheck.class.getNestMembers()[0] == VmCheck.class);
        byte[] hid;
        try (InputStream in = VmCheck.class.getResourceAsStream("VmCheck$Hid.class")) { hid = in.readAllBytes(); }
        MethodHandles.Lookup l = MethodHandles.lookup();
        Class<?> mate = l.defineHiddenClass(hid, false, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        Class<?> alone = l.defineHiddenClass(hid, false).lookupClass();
        row("hidden nestmate", (mate.getNestHost() == VmCheck.class) + " " + mate.isNestmateOf(A.class)
            + " " + (alone.getNestHost() == alone) + " " + alone.isNestmateOf(VmCheck.class)
            + " " + Arrays.asList(VmCheck.class.getNestMembers()).contains(mate));

        // an array is abstract and final with its element's access
        for (Class<?> c : new Class<?>[] { Priv[].class, Pkg[][].class, Prot[].class, VmCheck[].class, int[][].class, Map.Entry[].class })
            row("modifiers " + c.getSimpleName(), Modifier.toString(c.getModifiers()));

        // system packages: only the boot loader's, only once a class of it loads
        for (String n : new String[] {"java.util", "java.lang", "jdk.internal.misc", "javax.print", "no.such.pkg", "no-such-proc", "java/util", "java.sql"}) {
            Package p = Package.getPackage(n);
            row("getPackage " + n, p == null ? "null" : p.getName() + " " + p.isSealed());
        }
        row("same Package", (String.class.getPackage() == Package.getPackage("java.lang"))
            + " " + (java.sql.Date.class.getPackage() == ClassLoader.getPlatformClassLoader().getDefinedPackage("java.sql")));
        row("getPackages", Arrays.stream(Package.getPackages()).anyMatch(p -> p.getName().equals("java.util"))
            + " " + Arrays.stream(Package.getPackages()).anyMatch(p -> p.getName().equals("javax.print")));
        Class.forName("javax.print.DocFlavor");
        row("getPackage once loaded", Package.getPackage("javax.print") + " "
            + Arrays.stream(Package.getPackages()).anyMatch(p -> p.getName().equals("javax.print")));

        // properties the host and the launcher give
        Locale d = Locale.getDefault();
        String lang = System.getProperty("user.language"), country = System.getProperty("user.country");
        row("user.language", (lang != null) + " " + d.getLanguage().equals(lang) + " " + d.getCountry().equals(country == null ? "" : country));
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        // macOS's per-user directory (confstr), else /tmp
        row("java.io.tmpdir", tmp.isDirectory() + " " + tmp.canWrite() + " "
            + (System.getProperty("os.name").startsWith("Mac") ? tmp.getPath().endsWith("/T") && System.getProperty("java.io.tmpdir").endsWith("/T/")
               : System.getProperty("java.io.tmpdir").equals("/tmp")));
        row("sun.java.command", System.getProperty("sun.java.command") + " | " + System.getProperty("sun.java.launcher"));
        row("sun.boot.library.path", System.getProperty("sun.boot.library.path").equals(System.getProperty("java.home") + "/lib"));
        row("sun.io.unicode.encoding", System.getProperty("sun.io.unicode.encoding")
            .equals(System.getProperty("os.name").startsWith("Mac") ? "UnicodeBig" : "UnicodeLittle"));
    }
}
