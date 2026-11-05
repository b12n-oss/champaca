// System.exit inside main's uncaught-exception handler sets the status.
public class MainHandlerExits {
    public static void main(String[] args) {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> { System.out.println("main handler exits"); System.exit(5); });
        throw new RuntimeException("main");
    }
}
