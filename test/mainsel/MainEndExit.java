// System.exit in main: the process halts there, so main never ends and the
// hook sees it alive
public class MainEndExit {
    public static void main(String[] args) {
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            System.out.println("hook: main alive=" + main.isAlive())));
        System.exit(3);
    }
}
