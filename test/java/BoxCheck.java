import java.util.*;

// Boxing (Integer.valueOf and the other boxes' valueOf) is done at the call
// site: a value in a box's cache must still be the cached object, so two
// boxes of it are identical, and any other a fresh one each time, with the
// value, equals, hashCode and toString the JDK gives.
public class BoxCheck {
    static String same(Object a, Object b) { return (a == b ? "same" : "fresh") + (a.equals(b) ? "" : " UNEQUAL"); }
    public static void main(String[] args) {
        for (int i : new int[] {-129, -128, -1, 0, 1, 127, 128, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            Integer a = i, b = i;
            System.out.println("Integer " + i + " " + same(a, b) + " " + a + " " + a.hashCode() + " " + (a + 1));
        }
        for (long l : new long[] {-129L, -128L, 0L, 127L, 128L, 1L << 40, (1L << 60) - 1, 1L << 60, Long.MAX_VALUE, Long.MIN_VALUE}) {
            Long a = l, b = l;
            System.out.println("Long " + l + " " + same(a, b) + " " + a + " " + a.hashCode() + " " + (a - 1));
        }
        for (short s : new short[] {-129, -128, 0, 127, 128, Short.MAX_VALUE, Short.MIN_VALUE}) {
            Short a = s, b = s;
            System.out.println("Short " + s + " " + same(a, b) + " " + a + " " + a.hashCode());
        }
        for (char c : new char[] {0, 'a', 127, 128, 'é', '￿'}) {
            Character a = c, b = c;
            System.out.println("Character " + (int) c + " " + same(a, b) + " " + (int) a.charValue() + " " + a.hashCode());
        }
        for (double d : new double[] {0.0, -0.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            Double a = d, b = d;
            System.out.println("Double " + d + " " + same(a, b) + " " + a + " " + a.hashCode() + " " + a.isNaN());
        }
        for (float f : new float[] {0.0f, -0.0f, 1.5f, Float.NaN, Float.MAX_VALUE}) {
            Float a = f, b = f;
            System.out.println("Float " + f + " " + same(a, b) + " " + a + " " + a.hashCode());
        }
        Byte by = (byte) -128; Boolean bo = true;
        System.out.println("Byte " + by + " " + same(by, (Byte) (byte) -128) + " Boolean " + bo + " " + same(bo, (Boolean) true));
        Map<Integer, String> m = new HashMap<>();
        for (int i = -200; i < 2000; i += 7) m.put(i, "v" + i);
        long sum = 0;
        for (int i = -200; i < 2000; i++) { String v = m.get(i); if (v != null) sum += v.length() + i; }
        List<Long> longs = new ArrayList<>();
        for (long l = 1; l < 1L << 62; l *= 3) longs.add(l);
        System.out.println("map " + m.size() + " " + sum + " " + m.containsKey(1900) + " " + m.get(1900) + " longs " + longs.size() + " " + longs.get(longs.size() - 1) + " " + longs.contains(27L) + " " + longs.indexOf(81L));
        Object[] boxes = {1, 1L, (short) 1, 'x', 1.0, 1.0f, (byte) 1, true};
        StringBuilder kinds = new StringBuilder();
        for (Object o : boxes) kinds.append(o.getClass().getSimpleName()).append(' ');
        System.out.println(kinds + "" + Integer.valueOf(5).compareTo(7) + " " + Long.valueOf(9).equals(9) + " " + Integer.valueOf(300).equals(300));
    }
}
