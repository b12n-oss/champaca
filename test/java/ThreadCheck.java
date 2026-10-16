import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// Threads: start and join, synchronized, wait/notify, executors, latches,
// sleep and interrupt, CompletableFuture, ThreadLocal, spin-waits that only
// end if the other thread gets to run, a timed wait beside a notify, a
// failed static initializer, uncaught exceptions, shutdown hooks, and a
// non-daemon thread the launcher has to wait for.
public class ThreadCheck {
    static int counter = 0, blockCounter = 0;
    static final Object lock = new Object();
    static volatile boolean flag = false;
    static volatile int volatileTurn = -1;

    static class BadInit { static int x = Integer.parseInt("not a number"); }

    static synchronized void bump() { counter++; }

    public static void main(String[] args) throws Exception {
        // start and join
        int[] box = new int[1];
        Thread t = new Thread(() -> box[0] = 42, "worker");
        System.out.println("before start: " + t.getState() + " alive=" + t.isAlive());
        t.start();
        t.join();
        System.out.println("joined: " + box[0] + " " + t.getState() + " alive=" + t.isAlive());

        // synchronized: four threads, a synchronized static method and a block
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread w = new Thread(() -> {
                for (int j = 0; j < 5000; j++) {
                    bump();
                    synchronized (lock) { blockCounter++; }
                }
            });
            ts.add(w);
            w.start();
        }
        for (Thread w : ts) w.join();
        System.out.println("counters: " + counter + " " + blockCounter);

        // wait / notify: a one-slot queue
        final LinkedList<Integer> slot = new LinkedList<>();
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                synchronized (slot) {
                    while (!slot.isEmpty()) {
                        try { slot.wait(); } catch (InterruptedException e) { return; }
                    }
                    slot.add(i);
                    slot.notifyAll();
                }
            }
        });
        producer.start();
        int sum = 0;
        for (int i = 0; i < 5; i++) {
            synchronized (slot) {
                while (slot.isEmpty()) slot.wait();
                sum += slot.removeFirst();
                slot.notifyAll();
            }
        }
        producer.join();
        System.out.println("produced sum: " + sum);

        // an executor
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            final int n = i;
            fs.add(pool.submit(() -> n * n));
        }
        int squares = 0;
        for (Future<Integer> f : fs) squares += f.get();
        pool.shutdown();
        System.out.println("squares: " + squares + " terminated=" + pool.awaitTermination(5, TimeUnit.SECONDS));

        // a single-thread executor (the case that used to stop on a missing native)
        ExecutorService single = Executors.newSingleThreadExecutor();
        System.out.println("single: " + single.submit(() -> "ran on " + Thread.currentThread().getName().startsWith("pool")).get());
        single.shutdown();

        // a latch and an atomic
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger hits = new AtomicInteger();
        for (int i = 0; i < 3; i++) new Thread(() -> { hits.incrementAndGet(); latch.countDown(); }).start();
        latch.await();
        System.out.println("latch: " + hits.get());

        // ConcurrentHashMap from several threads
        ConcurrentHashMap<Integer, Integer> chm = new ConcurrentHashMap<>();
        ts.clear();
        for (int i = 0; i < 4; i++) {
            final int base = i * 100;
            Thread w = new Thread(() -> { for (int j = 0; j < 100; j++) chm.merge(base + j, 1, Integer::sum); });
            ts.add(w);
            w.start();
        }
        for (Thread w : ts) w.join();
        System.out.println("chm: " + chm.size());

        // sleep, and an interrupt that ends it
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                System.out.println("slept through");
            } catch (InterruptedException e) {
                System.out.println("interrupted: " + Thread.currentThread().isInterrupted());
            }
        });
        sleeper.start();
        Thread.sleep(20);
        sleeper.interrupt();
        sleeper.join();
        long t0 = System.nanoTime();
        Thread.sleep(50);
        System.out.println("slept >= 50ms: " + ((System.nanoTime() - t0) >= 50_000_000L));

        // CompletableFuture on the common pool
        CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> "async").thenApply(s -> s + "!");
        System.out.println("cf: " + cf.get(5, TimeUnit.SECONDS));

        // ThreadLocal
        ThreadLocal<String> tl = ThreadLocal.withInitial(() -> "initial");
        tl.set("main's");
        String[] seen = new String[1];
        Thread tlt = new Thread(() -> seen[0] = tl.get());
        tlt.start();
        tlt.join();
        System.out.println("thread local: " + tl.get() + " / " + seen[0]);

        // a spin-wait on a volatile, which needs the other thread to run
        new Thread(() -> flag = true).start();
        long spins = 0;
        while (!flag) spins++;
        System.out.println("spin ended: " + flag);

        // two threads that hand a turn back and forth by spinning: each one
        // only gets on if the other yields while it spins
        Thread pong = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                while (volatileTurn != 1) { }
                volatileTurn = 0;
            }
        });
        pong.start();
        for (int i = 0; i < 50; i++) {
            volatileTurn = 1;
            while (volatileTurn != 0) { }
        }
        pong.join();
        System.out.println("spin hand-offs: 50");

        // a timed wait that runs out, and a notify for the waiter still waiting
        final Object mon = new Object();
        Thread w1 = new Thread(() -> { synchronized (mon) { try { mon.wait(1); } catch (InterruptedException e) {} } });
        Thread w2 = new Thread(() -> { synchronized (mon) { try { mon.wait(); } catch (InterruptedException e) {} } });
        w1.start();
        Thread.sleep(20);
        w2.start();
        Thread.sleep(20);
        synchronized (mon) {
            Thread.sleep(50);   // w1's timeout passes while main holds mon
            mon.notify();
        }
        w2.join(2000);
        w1.join(2000);
        System.out.println("timed wait and notify: " + !w1.isAlive() + " " + !w2.isAlive());

        // a static initializer that throws: the class is unusable afterwards
        for (int i = 0; i < 2; i++) {
            try {
                System.out.println("bad init: " + BadInit.x);
            } catch (Throwable e) {
                System.out.println("bad init: " + e.getClass().getName());
            }
        }

        // an uncaught exception goes to the handler
        Thread bad = new Thread(() -> { throw new IllegalStateException("boom"); }, "bad");
        bad.setUncaughtExceptionHandler((th, e) -> System.out.println("uncaught in " + th.getName() + ": " + e));
        bad.start();
        bad.join();

        // a daemon that never ends doesn't hold the process open
        Thread daemon = new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) {} });
        daemon.setDaemon(true);
        daemon.start();

        // a shutdown hook, and a non-daemon thread still running when main returns
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("shutdown hook")));
        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            System.out.println("late thread done");
        }).start();
        System.out.println("main done");
    }
}
