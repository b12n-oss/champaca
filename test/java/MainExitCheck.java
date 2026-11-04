import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

// What other threads see of the main thread once main returns: the launcher
// detaches it (Thread.exit, terminated, joiners notified) before it waits
// for the non-daemon threads and runs the shutdown hooks. Each thread keeps
// its rows and the hook prints them in a fixed order, so thread timing
// doesn't reorder the output.
public class MainExitCheck {
    static volatile boolean returning;
    static final CountDownLatch started = new CountDownLatch(3);

    static final List<String> mainRows = new ArrayList<>(), joinerRows = new ArrayList<>(),
        waiterRows = new ArrayList<>(), untimedRows = new ArrayList<>();

    static void row(List<String> rows, String name, Object v) {
        synchronized (rows) { rows.add(name + ": " + v); }
    }

    static void print(List<String> rows) {
        synchronized (rows) { rows.forEach(System.out::println); }
    }

    public static void main(String[] args) throws Exception {
        Thread main = Thread.currentThread();
        row(mainRows, "alive before return", main.isAlive());
        row(mainRows, "state before return", main.getState());
        row(mainRows, "listed before return", Thread.getAllStackTraces().containsKey(main));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            row(mainRows, "hook: main alive", main.isAlive());
            row(mainRows, "hook: main state", main.getState());
            print(mainRows); print(joinerRows); print(waiterRows); print(untimedRows);
        }));
        Thread joiner = new Thread(() -> {
            try {
                started.countDown();
                long t0 = System.nanoTime();
                main.join(10000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                row(joinerRows, "join returned before timeout", ms < 9000);
                row(joinerRows, "returning seen", returning);
                row(joinerRows, "alive after join", main.isAlive());
                row(joinerRows, "state after join", main.getState());
                row(joinerRows, "listed after join", Thread.getAllStackTraces().containsKey(main));
                boolean inArray = false;
                Thread[] all = new Thread[Thread.activeCount() + 8];
                int n = Thread.enumerate(all);
                for (int i = 0; i < n; i++) if (all[i] == main) inArray = true;
                row(joinerRows, "enumerated after join", inArray);
                row(joinerRows, "join(1) after end", joinQuick(main));
            } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        }, "joiner");
        Thread waiter = new Thread(() -> {
            try {
                synchronized (main) {
                    started.countDown();
                    while (main.isAlive()) main.wait(10000);
                }
                row(waiterRows, "waiter: woken, main alive", main.isAlive());
            } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        }, "waiter");
        Thread untimed = new Thread(() -> {
            try {
                started.countDown();
                main.join();
                row(untimedRows, "untimed join returned", !main.isAlive());
            } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        }, "untimed");
        joiner.start(); waiter.start(); untimed.start();
        started.await();
        Thread.sleep(200);
        row(mainRows, "main still alive at the end", main.isAlive());
        returning = true;
    }

    static boolean joinQuick(Thread t) throws InterruptedException {
        long t0 = System.nanoTime();
        t.join(1);
        return (System.nanoTime() - t0) / 1_000_000 < 500;
    }
}
