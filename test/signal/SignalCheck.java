// A program that never ends by itself: a shutdown hook, then a non-daemon
// thread sleeping, as a server's. jolt test (the signal row) starts it,
// waits for "ready", sends SIGTERM or SIGHUP, and wants the hook's line and
// the status HotSpot 25.0.2 exits with, 128 + the signal (143, 129).
public class SignalCheck {
  public static void main(String[] a) throws Exception {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { System.out.println("hook ran"); System.out.flush(); }));
    Thread t = new Thread(() -> { try { Thread.sleep(600_000); } catch (InterruptedException e) { } });
    t.start();
    System.out.println("ready");
    System.out.flush();
  }
}
