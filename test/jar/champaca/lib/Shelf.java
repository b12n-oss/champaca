package champaca.lib;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

// Library code that only reaches the runtime from inside a jar.
public class Shelf {
  public static int count = 3;

  // java.lang.reflect.Array, the way generic library code builds arrays
  @SuppressWarnings("unchecked")
  public static <T> T[] fill(Class<T> type, int n, T v) {
    T[] a = (T[]) Array.newInstance(type, n);
    for (int i = 0; i < Array.getLength(a); i++) Array.set(a, i, v);
    return a;
  }

  public static int sum(int[] xs) {
    int s = 0;
    for (int i = 0; i < Array.getLength(xs); i++) s += (Integer) Array.get(xs, i);
    return s;
  }

  public static int[][] grid(int r, int c) { return (int[][]) Array.newInstance(int.class, r, c); }

  // sun.misc.Unsafe lives in the jdk.unsupported module, not java.base
  public static int bumpThroughUnsafe() throws Exception {
    Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
    f.setAccessible(true);
    sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
    Field c = Shelf.class.getDeclaredField("count");
    Object base = u.staticFieldBase(c);
    long off = u.staticFieldOffset(c);
    u.putInt(base, off, u.getInt(base, off) + 1);
    return count;
  }
}
