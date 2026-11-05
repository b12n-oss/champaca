// An uncaught-exception handler that throws: HotSpot prints one line naming
// the handler's exception and the thread, for a started thread and for main.
public class HandlerThrows {
    public static void main(String[] args) throws Exception {
        Thread w = new Thread(() -> { throw new IllegalArgumentException("task"); }, "worker-1");
        w.setUncaughtExceptionHandler((t, e) -> {
            System.out.println("handler saw " + e.getMessage() + " on " + t.getName());
            throw new IllegalStateException("handler");
        });
        w.start();
        w.join();
        System.out.println("worker alive " + w.isAlive());
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            System.out.println("main handler saw " + e.getMessage());
            throw new UnsupportedOperationException("main handler");
        });
        throw new RuntimeException("main");
    }
}
