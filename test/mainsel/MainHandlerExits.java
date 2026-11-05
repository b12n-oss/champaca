// System.exit inside main's uncaught-exception handler sets the status, and
// the process ends without waiting for a non-daemon thread still running.
public class MainHandlerExits {
    public static void main(String[] args) {
        Thread sleeper = new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException e) { }
            System.out.println("sleeper woke");
        }, "sleeper");
        sleeper.start();
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> { System.out.println("main handler exits"); System.exit(5); });
        throw new RuntimeException("main");
    }
}
