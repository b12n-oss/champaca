// A recursion without end is a StackOverflowError a program can catch, as on
// the JVM: by its class, as an Error or a Throwable, with a null message and a
// stack trace; the program goes on, and it happens again the next time, on
// another thread too, and after the depth-probing idiom. (A try/finally in
// every frame unwinds in time quadratic in the depth, limitations.md: no row.) Before, jolt's own
// stack limit (gigabytes) ended the process with a Scheme error.
public class StackOverflowCheck {
    static int depth;
    static int nontail(int n) { depth++; return nontail(n + 1) + 1; }
    static long locals(long a, long b) { depth++; long c = a * 31 + b; return locals(c, a) ^ c; }
    static String overflow(Runnable r) {
        depth = 0;
        try { r.run(); return "no overflow"; }
        catch (StackOverflowError e) {
            return e.getClass().getName() + " message " + e.getMessage() + " deep " + (depth > 1000) + " trace " + (e.getStackTrace().length > 0)
                   + " cause " + e.getCause();
        }
    }
    public static void main(String[] args) throws Exception {
        System.out.println("nontail: " + overflow(() -> nontail(0)));
        System.out.println("locals: " + overflow(() -> locals(1, 2)));
        try { nontail(0); } catch (Error e) { System.out.println("as Error: " + e.getClass().getSimpleName()); }
        try { locals(3, 4); } catch (Throwable e) { System.out.println("as Throwable: " + e.getClass().getSimpleName()); }
        try { nontail(0); } catch (RuntimeException e) { System.out.println("as RuntimeException: wrong"); } catch (VirtualMachineError e) { System.out.println("as VirtualMachineError: " + e.getClass().getSimpleName()); }
        int again = 0;
        for (int i = 0; i < 3; i++) { try { nontail(0); } catch (StackOverflowError e) { again++; } }
        System.out.println("again " + again);
        String[] fromThread = new String[1];
        Thread t = new Thread(() -> fromThread[0] = overflow(() -> nontail(0)));
        t.start();
        t.join();
        System.out.println("thread: " + fromThread[0]);
        System.out.println("after: " + nontailDepth(100));
        for (int i = 0; i < 3; i++) { depth = 0; max = 0; probe(); System.out.println("probe " + i + " deep " + (max > 1000)); }
        System.out.println("after probes: " + overflow(() -> nontail(0)));
    }
    static int nontailDepth(int n) { return n == 0 ? 0 : 1 + nontailDepth(n - 1); }
    // the depth-probing idiom: each frame catches the overflow itself, so the
    // catch that stops it is deep, and the next recursion must overflow again
    static int max;
    static void probe() { try { depth++; probe(); } catch (StackOverflowError e) { if (depth > max) max = depth; } }
}
