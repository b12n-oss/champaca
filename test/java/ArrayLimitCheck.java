import java.lang.reflect.Array;
import java.util.Arrays;

/** Arrays too large to make: OutOfMemoryError with HotSpot's two messages,
 *  and the program goes on. The sizes are past any heap a test machine has:
 *  the smallest that must fail for lack of room is 17 GB of longs. */
public class ArrayLimitCheck {
    interface Maker { Object make(); }
    static void row(String name, Maker m) {
        String r;
        try {
            Object a = m.make();
            r = "made " + Array.getLength(a);
        } catch (OutOfMemoryError e) {
            r = e.toString();
        } catch (Throwable e) {
            r = "other: " + e;
        }
        System.out.println(name + ": " + r);
    }
    static int n(int below) { return Integer.MAX_VALUE - below; }

    public static void main(String[] args) {
        // a length past the VM's limit, whatever the element
        row("byte[MAX]", () -> new byte[n(0)]);
        row("byte[MAX-1]", () -> new byte[n(1)]);
        row("long[MAX]", () -> new long[n(0)]);
        row("char[MAX-1]", () -> new char[n(1)]);
        row("Object[MAX]", () -> new Object[n(0)]);
        row("String[MAX-1]", () -> new String[n(1)]);
        // within the limit, with no room for it
        row("long[MAX-2]", () -> new long[n(2)]);
        row("double[MAX-2]", () -> new double[n(2)]);
        // several dimensions: each inner array small, all of them too much
        row("long[100000][100000]", () -> new long[100000][100000]);
        row("int[60000][60000][4]", () -> new int[60000][60000][4]);
        row("long[2][MAX]", () -> new long[2][n(0)]);
        row("long[MAX][2]", () -> new long[n(0)][2]);
        // the dimensions are taken in order, so what the first one does
        // comes before a negative length further in
        row("long[MAX][-1]", () -> new long[n(0)][-1]);
        row("long[MAX-2][-1]", () -> new long[n(2)][-1]);
        row("long[3][-1]", () -> new long[3][-1]);
        row("long[-1][MAX]", () -> new long[-1][n(0)]);
        row("long[0][-1]", () -> new long[0][-1]);
        row("long[0][MAX]", () -> new long[0][n(0)]);
        // through reflection and the library
        row("Array.newInstance(int, MAX)", () -> Array.newInstance(int.class, n(0)));
        row("Array.newInstance(long, MAX-2)", () -> Array.newInstance(long.class, n(2)));
        row("Array.newInstance(long, 100000, 100000)", () -> Array.newInstance(long.class, 100000, 100000));
        row("Arrays.copyOf(MAX)", () -> Arrays.copyOf(new int[1], n(0)));
        row("StringBuilder(MAX)", () -> { new StringBuilder(n(0)); return new int[0]; });
        // and after all that, arrays that fit are made as ever
        row("int[1 << 20]", () -> new int[1 << 20]);
        row("long[300][5000]", () -> new long[300][5000]);
        row("byte[0]", () -> new byte[0]);
        long[] a = new long[1 << 18];
        a[a.length - 1] = 7;
        System.out.println("last " + a[a.length - 1]);
    }
}
