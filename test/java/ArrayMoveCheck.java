import java.util.Arrays;

// System.arraycopy and clone copy whole runs at once (Chez's vector-copy!):
// overlapping moves in both directions within one array, every element
// kind, zero lengths, the copy's independence from its source, and the
// library methods built on them.
public class ArrayMoveCheck {
    static String show(Object a) {
        if (a instanceof byte[] b) return Arrays.toString(b);
        if (a instanceof int[] i) return Arrays.toString(i);
        if (a instanceof long[] l) return Arrays.toString(l);
        if (a instanceof char[] c) return Arrays.toString(c);
        if (a instanceof double[] d) return Arrays.toString(d);
        return Arrays.toString((Object[]) a);
    }
    public static void main(String[] args) {
        for (int[] move : new int[][] {{0, 3, 5}, {3, 0, 5}, {0, 1, 7}, {1, 0, 7}, {2, 2, 4}, {0, 4, 4}, {4, 0, 4}, {0, 7, 1}, {5, 5, 0}, {8, 8, 0}}) {
            byte[] b = {0, 1, 2, 3, 4, 5, 6, 7};
            int[] i = {0, 10, 20, 30, 40, 50, 60, 70};
            long[] l = {0, 1L << 40, 2, 3, 4, 5, 6, Long.MIN_VALUE};
            char[] c = "abcdefgh".toCharArray();
            double[] d = {0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, Double.NaN};
            String[] s = {"a", "b", null, "d", "e", "f", "g", "h"};
            for (Object a : new Object[] {b, i, l, c, d, s}) {
                System.arraycopy(a, move[0], a, move[1], move[2]);
                System.out.println(move[0] + "->" + move[1] + " x" + move[2] + " " + show(a));
            }
        }
        int[] src = {1, 2, 3, 4};
        int[] copy = src.clone();
        copy[0] = 99;
        int[][] grid = {{1, 2}, {3, 4}};
        int[][] shallow = grid.clone();
        shallow[0][0] = 7;
        shallow[1] = new int[] {9};
        System.out.println("clone " + Arrays.toString(src) + " " + Arrays.toString(copy) + " " + Arrays.deepToString(grid) + " " + Arrays.deepToString(shallow)
            + " " + new byte[0].clone().length + " " + (src.clone() != src));
        byte[] bytes = {1, 2, 3, 4, 5};
        System.out.println("copyOfRange " + Arrays.toString(Arrays.copyOfRange(bytes, 1, 4)) + " " + Arrays.toString(Arrays.copyOfRange(bytes, 3, 8))
            + " " + Arrays.toString(Arrays.copyOfRange(bytes, 5, 5)) + " " + Arrays.toString(Arrays.copyOf(bytes, 2)) + " " + Arrays.toString(Arrays.copyOf(bytes, 7)));
        for (int[] bad : new int[][] {{-1, 2}, {6, 7}, {3, 2}}) {
            try {
                System.out.println(Arrays.toString(Arrays.copyOfRange(bytes, bad[0], bad[1])));
            } catch (RuntimeException e) {
                System.out.println("copyOfRange " + bad[0] + " " + bad[1] + ": " + e);
            }
        }
        StringBuilder sb = new StringBuilder("abcdef");
        sb.insert(2, "XY").deleteCharAt(0).replace(1, 3, "--").insert(0, sb);
        System.out.println("builder " + sb + " " + "hello world".substring(3, 9) + " " + "x".repeat(5) + " " + new String(new char[] {'a', 'b', 'c'}, 1, 2));
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>(java.util.List.of(1, 2, 3, 4, 5, 6));
        list.add(2, 42); list.remove(0); list.addAll(1, java.util.List.of(7, 8)); list.subList(3, 5).clear();
        System.out.println("list " + list);
    }
}
