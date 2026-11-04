// main ends with an uncaught exception: the handler reports it while main is
// alive, then main ends and a joiner sees it terminated
public class MainEndThrows {
    public static void main(String[] args) {
        Thread main = Thread.currentThread();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            System.out.println("handler: " + t.getName() + " alive=" + t.isAlive() + " " + e.getMessage()));
        new Thread(() -> {
            try {
                main.join(10000);
                System.out.println("joined: alive=" + main.isAlive() + " state=" + main.getState());
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        }).start();
        throw new IllegalStateException("main fails");
    }
}
