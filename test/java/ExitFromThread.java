// System.exit on a thread other than main ends the process from there: the
// shutdown hooks run, and main, still sleeping, never prints again.
public class ExitFromThread {
    public static void main(String[] a) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook")));
        new Thread(() -> { System.out.println("worker exits"); System.exit(0); }).start();
        Thread.sleep(10_000);
        System.out.println("not reached");
    }
}
