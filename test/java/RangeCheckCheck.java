import java.util.*;

// Objects.checkIndex, checkFromToIndex, checkFromIndexSize and requireNonNull
// are done at the call site, as are the Preconditions methods under them:
// what each returns in range, and the JDK's exception and message out of it,
// called directly and through the collections and strings built on them.
public class RangeCheckCheck {
    interface Call { Object run(); }
    static void show(String what, Call c) {
        try {
            System.out.println(what + " = " + c.run());
        } catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }
    public static void main(String[] args) {
        int[][] index = {{0, 1}, {4, 5}, {5, 5}, {-1, 5}, {0, 0}, {7, 3}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {Integer.MIN_VALUE, 2}, {2, -1}};
        for (int[] p : index) show("checkIndex(" + p[0] + ", " + p[1] + ")", () -> Objects.checkIndex(p[0], p[1]));
        int[][] ranges = {{0, 0, 0}, {1, 3, 5}, {0, 5, 5}, {3, 2, 5}, {-1, 2, 5}, {2, 6, 5}, {5, 5, 5}, {0, 1, -1},
                          {Integer.MAX_VALUE, 1, Integer.MAX_VALUE}, {1, Integer.MAX_VALUE, Integer.MAX_VALUE}, {Integer.MIN_VALUE, 0, 0}};
        for (int[] p : ranges) {
            show("checkFromToIndex(" + p[0] + ", " + p[1] + ", " + p[2] + ")", () -> Objects.checkFromToIndex(p[0], p[1], p[2]));
            show("checkFromIndexSize(" + p[0] + ", " + p[1] + ", " + p[2] + ")", () -> Objects.checkFromIndexSize(p[0], p[1], p[2]));
        }
        show("requireNonNull(x)", () -> Objects.requireNonNull("x"));
        show("requireNonNull(null)", () -> Objects.requireNonNull(null));
        show("requireNonNull(x, msg)", () -> Objects.requireNonNull("x", "named"));
        show("requireNonNull(null, msg)", () -> Objects.requireNonNull(null, "must not be null"));
        show("requireNonNull(null, null)", () -> Objects.requireNonNull(null, (String) null));
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        show("ArrayList.get(2)", () -> list.get(2));
        show("ArrayList.get(3)", () -> list.get(3));
        show("ArrayList.get(-1)", () -> list.get(-1));
        show("ArrayList.set(5)", () -> list.set(5, "z"));
        show("ArrayList.remove(9)", () -> list.remove(9));
        show("ArrayList.subList(2, 1)", () -> list.subList(2, 1));
        show("ArrayList.subList(0, 4)", () -> list.subList(0, 4));
        show("List.of.get(7)", () -> List.of(1, 2).get(7));
        show("charAt(9)", () -> "abc".charAt(9));
        show("substring(2, 1)", () -> "abc".substring(2, 1));
        show("substring(4)", () -> "abc".substring(4));
        show("StringBuilder.charAt(3)", () -> new StringBuilder("abc").charAt(3));
        show("StringBuilder.setLength(-1)", () -> { new StringBuilder().setLength(-1); return "set"; });
        show("Arrays.copyOfRange(3, 2)", () -> Arrays.copyOfRange(new int[5], 3, 2).length);
        show("ByteBuffer.get(8)", () -> java.nio.ByteBuffer.allocate(4).get(8));
        show("String(bytes, 2, 9)", () -> new String(new byte[4], 2, 9));
        show("Optional.of(null)", () -> Optional.of(null));
        show("HashMap.computeIfAbsent(k, null)", () -> new HashMap<String, String>().computeIfAbsent("k", null));
        long sum = 0;
        for (int i = 0; i < 100000; i++) sum += Objects.checkIndex(i % 7, 7) + Objects.requireNonNull(list).size() + list.get(i % 3).length();
        System.out.println("sum " + sum);
    }
}
