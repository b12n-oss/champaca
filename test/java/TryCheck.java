import java.util.ArrayList;
import java.util.List;

// Exception handlers that see locals assigned inside the try, nested and
// sequential try regions, loops that throw part way, and handlers inside
// their own range (synchronized blocks).
public class TryCheck {
    static int boom(int x) {
        if (x == 3) throw new IllegalArgumentException("three");
        if (x == 5) throw new IllegalStateException("five");
        return x;
    }

    // the handler reads i and sum, both assigned inside the try
    static String loopThrows() {
        int i = 0;
        long sum = 0;
        try {
            for (i = 0; i < 10; i++) sum += boom(i);
        } catch (IllegalArgumentException e) {
            return "caught at " + i + " sum " + sum + " " + e.getMessage();
        }
        return "no throw";
    }

    // the handler reads a local set before the try and never changed in it
    static String setBefore(int x) {
        String tag = "tag" + x;
        try {
            boom(x);
            return "ok " + tag;
        } catch (RuntimeException e) {
            return tag + " " + e.getMessage();
        }
    }

    // two catch clauses in order, nested tries, rethrow to the outer try
    static String nested(int x) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("a");
            try {
                sb.append("b");
                boom(x);
                sb.append("c");
            } catch (IllegalArgumentException e) {
                sb.append("[inner ").append(e.getMessage()).append("]");
                throw new RuntimeException("again");
            } finally {
                sb.append("f");
            }
            sb.append("d");
        } catch (IllegalStateException e) {
            sb.append("[outer ").append(e.getMessage()).append("]");
        } catch (RuntimeException e) {
            sb.append("[outer rt ").append(e.getMessage()).append("]");
        }
        return sb.toString();
    }

    // a try inside a loop: each iteration enters and leaves the region
    static String tryInLoop() {
        List<String> out = new ArrayList<>();
        int caught = 0;
        for (int i = 0; i < 7; i++) {
            try {
                out.add(Integer.toString(boom(i)));
            } catch (IllegalArgumentException | IllegalStateException e) {
                caught++;
                out.add("x" + i);
            }
        }
        return out + " caught " + caught;
    }

    // sequential try regions with a value carried between them
    static int sequential(int x) {
        int v = x;
        try { v = boom(v) + 1; } catch (IllegalArgumentException e) { v = 100; }
        try { v = boom(v) * 2; } catch (RuntimeException e) { v = -v; }
        return v;
    }

    // the exception leaves through a method with no handler for it
    static int passThrough(int x) {
        try {
            return boom(x);
        } catch (IllegalStateException e) {
            return -5;
        }
    }

    static String caller(int x) {
        try {
            return "got " + passThrough(x);
        } catch (IllegalArgumentException e) {
            return "caller caught " + e.getMessage();
        }
    }

    // a throw from inside the catch block, and from inside finally
    static String fromHandler() {
        try {
            try {
                boom(3);
            } catch (IllegalArgumentException e) {
                boom(5);
            }
        } catch (IllegalStateException e) {
            return "handler threw " + e.getMessage();
        }
        return "none";
    }

    static int finallyOverrides() {
        int r = 0;
        try {
            r = 1;
            boom(3);
        } catch (IllegalArgumentException e) {
            r = 2;
            return r;
        } finally {
            r = 3;
        }
        return r;
    }

    static final Object LOCK = new Object();
    static int counter;

    // synchronized blocks: the any-handler covers itself; the monitor must
    // be released when the block throws
    static String syncBlock() {
        int caught = 0;
        for (int i = 0; i < 4; i++) {
            try {
                synchronized (LOCK) {
                    counter++;
                    if (i % 2 == 1) throw new IllegalStateException("odd");
                }
            } catch (IllegalStateException e) {
                caught++;
            }
        }
        return "counter " + counter + " caught " + caught + " held " + Thread.holdsLock(LOCK);
    }

    // a long assigned in the try, read in the handler
    static long wideLocal() {
        long acc = 1;
        try {
            while (true) {
                acc *= 3;
                if (acc > 1000) throw new ArithmeticException("big");
            }
        } catch (ArithmeticException e) {
            return acc;
        }
    }

    // many catches in a row: the handler stack has to come back each time
    static int manyCatches() {
        int n = 0;
        for (int i = 0; i < 1000; i++) {
            try { boom(3); } catch (IllegalArgumentException e) { n++; }
        }
        return n;
    }

    // exceptions caught on another thread
    static String onThread() throws InterruptedException {
        String[] out = new String[1];
        Thread t = new Thread(() -> out[0] = loopThrows() + " / " + nested(5));
        t.start();
        t.join();
        return out[0];
    }

    public static void main(String[] args) throws Exception {
        System.out.println(loopThrows());
        System.out.println(setBefore(3) + " | " + setBefore(4));
        System.out.println(nested(1) + " | " + nested(3) + " | " + nested(5));
        System.out.println(tryInLoop());
        System.out.println(sequential(2) + " " + sequential(3) + " " + sequential(4));
        System.out.println(caller(1) + " | " + caller(3) + " | " + caller(5));
        System.out.println(fromHandler());
        System.out.println(finallyOverrides());
        System.out.println(syncBlock());
        System.out.println(wideLocal());
        System.out.println(manyCatches());
        System.out.println(onThread());
        try {
            boom(5);
        } catch (RuntimeException e) {
            System.out.println("main caught " + e.getClass().getName() + " " + e.getMessage());
        }
    }
}
