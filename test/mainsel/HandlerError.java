// A default uncaught-exception handler that throws an Error: HotSpot reports
// it for the worker and the process still ends normally when main returns.
public class HandlerError {
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { throw new AssertionError("default " + t.getName()); });
        Thread w = new Thread(() -> { throw new RuntimeException("x"); });
        w.start();
        w.join();
        System.out.println("main returns");
    }
}
