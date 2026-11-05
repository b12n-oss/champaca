// System.exit inside a started thread's uncaught-exception handler ends the
// process with that status while main still sleeps.
public class HandlerExits {
    public static void main(String[] args) throws Exception {
        Thread w = new Thread(() -> { throw new RuntimeException("w"); }, "worker-2");
        w.setUncaughtExceptionHandler((t, e) -> { System.out.println("worker handler exits"); System.exit(4); });
        w.start();
        Thread.sleep(60000);
    }
}
