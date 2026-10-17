import java.util.*;

// aastore and System.arraycopy check what they store against the array's
// element type, in the JVM's order: null array, index, then the value.
public class ArrayStoreCheck {
  interface Shape {}
  static class Sq implements Shape {}
  static class Big extends Sq {}

  static String run(Runnable r) {
    try { r.run(); return "ok"; }
    catch (RuntimeException e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
  }

  public static void main(String[] args) {
    Object[] strs = new String[2];
    System.out.println(run(() -> strs[0] = 1));
    System.out.println(run(() -> strs[0] = "s") + " " + strs[0]);
    System.out.println(run(() -> strs[1] = null) + " " + strs[1]);
    System.out.println(run(() -> strs[5] = 1));
    try { ((Object[]) null)[0] = 1; } catch (NullPointerException e) { System.out.println("npe"); }
    Object[] shapes = new Shape[3];
    System.out.println(run(() -> shapes[0] = new Big()) + " " + run(() -> shapes[1] = "no"));
    Object[] sqs = new Sq[1];
    System.out.println(run(() -> sqs[0] = new Big()) + " " + run(() -> sqs[0] = new Shape() {}));
    Object[] rows = new int[2][];
    System.out.println(run(() -> rows[0] = new int[3]) + " " + run(() -> rows[1] = new long[3]) + " " + run(() -> rows[1] = new Object[0]));
    Object[] ser = new java.io.Serializable[2];
    System.out.println(run(() -> ser[0] = new int[1]) + " " + run(() -> ser[1] = "s") + " " + run(() -> ser[1] = new Object()));
    Object[] seqs = new CharSequence[4];
    for (int i = 0; i < 4; i++) {
      int k = i;
      System.out.print(run(() -> seqs[k] = (k % 2 == 0) ? "s" : new StringBuilder("b")) + " ");
    }
    System.out.println(run(() -> seqs[0] = 3));
    Object[] same = new String[1];
    for (int i = 0; i < 3; i++) System.out.print(run(() -> same[0] = i0(same)) + " ");
    System.out.println();

    // one store site seeing a good value, a bad one, then other arrays
    Object[][] targets = {new String[1], new String[1], new Integer[1], new Object[1], new String[1], new Shape[1]};
    Object[] values = {"s", 1, "t", 2, new Sq(), new Sq()};
    for (int i = 0; i < targets.length; i++) {
      Object[] t = targets[i]; Object v = values[i];
      System.out.print(run(() -> put(t, v)) + " | ");
    }
    System.out.println();
    for (Object v : new Object[] {"s", 1, "t", new StringBuilder()}) {
      Object[] fresh = new String[1];
      System.out.print(run(() -> put(fresh, v)) + " | ");
    }
    System.out.println();

    // arraycopy
    Object[] mixed = {"a", "b", 3, "d"};
    String[] dst = new String[4];
    System.out.println(run(() -> System.arraycopy(mixed, 0, dst, 0, 4)) + " " + Arrays.toString(dst));
    System.out.println(run(() -> System.arraycopy(new int[2], 0, new long[2], 0, 2)));
    System.out.println(run(() -> System.arraycopy(new int[2], 0, new Object[2], 0, 2)));
    System.out.println(run(() -> System.arraycopy("x", 0, new Object[2], 0, 1)));
    System.out.println(run(() -> System.arraycopy(new Object[2], 0, "x", 0, 1)));
    System.out.println(run(() -> System.arraycopy(new int[2], 0, new int[2], 1, 2)));
    System.out.println(run(() -> System.arraycopy(new String[3], -1, new String[2], 0, 1)));
    System.out.println(run(() -> System.arraycopy(new String[3], 0, new String[2], 0, -1)));
    System.out.println(run(() -> System.arraycopy(null, 0, new String[2], 0, 1)));
    String[] ok = new String[3];
    System.arraycopy(new Object[] {"p", "q"}, 0, ok, 1, 2);
    System.out.println(Arrays.toString(ok) + " " + Arrays.toString(Arrays.copyOf(new Object[] {"r"}, 2, String[].class)));
    System.out.println(run(() -> Arrays.copyOf(new Object[] {"r", 1}, 2, String[].class)));
    List<Object> list = new ArrayList<>(List.of("a", "b"));
    System.out.println(run(() -> list.toArray(new Integer[0])));
    System.out.println(run(() -> java.lang.reflect.Array.set(new String[1], 0, 1)));
  }

  static Object i0(Object[] a) { return "s"; }
  static void put(Object[] a, Object v) { a[0] = v; }
}
