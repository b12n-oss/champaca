import java.util.*;

// The JVM's answers for examples/varargs.clj: same calls, same labels.
public class VarargsCheck {
    static void p(String label, Object v) { System.out.println(label + " " + v); }
    public static void main(String[] args) {
        p("list-of-3", List.of("a", "b", "c"));
        p("list-of-1", List.of("x"));
        p("list-of-0", List.of());
        p("list-of-12", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
        p("asList", Arrays.asList(1, 2, 3));
        p("join", String.join("-", "x", "y", "z"));
        p("hash", Objects.hash(1, 2, 3));
        p("toString-int[]", Arrays.toString(new int[]{3, 1, 2}));
        p("toString-String[]", Arrays.toString(new String[]{"p", "q"}));
        p("deepToString", Arrays.deepToString(new int[][]{{1, 2}, {3}}));
        p("format", String.format("%s has %d items at %.2f each, %b", "cart", 3, 2.5, true));
        p("format-pad", String.format("[%5d|%-4s|%x]", 42, "ab", 255));
        p("max-of", Collections.max(Arrays.asList(4, 9, 2)));
        p("enumset", EnumSet.of(Thread.State.NEW, Thread.State.BLOCKED));
    }
}
