import java.util.ArrayList;
import java.util.List;

/** The heap filled until nothing more fits: OutOfMemoryError, which the
 *  program catches and goes on from, more than once. bb test runs this with
 *  a small heap (JOLT_MAX_HEAP), and HotSpot's output was recorded with
 *  -Xmx256m. Each array is under the size at which champaca checks a
 *  request before making it, so the error comes from the collector. */
public class HeapFullCheck {
    static List<long[]> kept = new ArrayList<>();

    static void fillKept() {
        while (true) kept.add(new long[100000]);
    }
    /** The list is a local here: gone once this frame is. */
    static int fillLocal() {
        List<long[]> mine = new ArrayList<>();
        while (true) mine.add(new long[100000]);
    }

    public static void main(String[] args) {
        for (int round = 1; round <= 3; round++) {
            try {
                fillKept();
                System.out.println("round " + round + ": filled without end?");
            } catch (OutOfMemoryError e) {
                boolean many = kept.size() > 100;
                kept = null;                  // first: nothing fits until this goes
                kept = new ArrayList<>();
                System.out.println("round " + round + ": " + e.getMessage() + ", after many arrays: " + many
                                   + ", a VirtualMachineError: " + (e instanceof VirtualMachineError));
            }
        }
        try {
            fillLocal();
        } catch (Throwable t) {
            System.out.println("local: " + t.getClass().getName() + ": " + t.getMessage());
        }
        long[] a = new long[200000];
        a[a.length - 1] = 9;
        System.out.println("after: " + a.length + " " + a[a.length - 1]);
    }
}
