import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// Virtual threads give the JVM's results. champaca has no continuations, so the
// JDK runs each as a platform thread (ThreadBuilders.BoundVirtualThread), which
// nothing here can tell apart from the JVM's.
public class VirtualThreadCheck {
    static void row(String label, Object v) { System.out.println(label + ": " + v); }

    public static void main(String[] args) throws Exception {
        StringBuilder seen = new StringBuilder();
        Thread t = Thread.ofVirtual().name("v1").start(() -> seen.append("ran ").append(Thread.currentThread().isVirtual()));
        t.join();
        row("ofVirtual", seen + " " + t.getName() + " " + t.isVirtual() + " " + t.isDaemon());
        Thread s = Thread.startVirtualThread(() -> {});
        s.join();
        row("startVirtualThread", s.isVirtual() + " " + s.isAlive());
        row("unstarted state", Thread.ofVirtual().unstarted(() -> {}).getState());
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 1; i <= 5; i++) { final int n = i; fs.add(ex.submit(() -> n * n)); }
            int sum = 0;
            for (Future<Integer> f : fs) sum += f.get();
            row("per-task executor", sum);
        }
        Thread.Builder b = Thread.ofVirtual().name("w", 0);
        Thread w0 = b.unstarted(() -> {}), w1 = b.unstarted(() -> {});
        row("builder names", w0.getName() + " " + w1.getName());
        Thread boom = Thread.ofVirtual().unstarted(() -> { throw new IllegalStateException("boom"); });
        boom.setUncaughtExceptionHandler((th, e) -> System.out.println("handler: " + e.getMessage() + " virtual " + th.isVirtual()));
        boom.start();
        boom.join();
        row("current is virtual", Thread.currentThread().isVirtual());
    }
}
