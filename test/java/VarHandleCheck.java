import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

// VarHandles of each kind (instance field, static field, array element,
// byte[] view) and each access-mode family, plus the java.util.concurrent
// classes built on them.
public class VarHandleCheck {
  int count;
  long total;
  String name = "a";
  static int hits;
  static class Base { int inherited = 7; }
  static class Sub extends Base {}

  public static void main(String[] args) throws Exception {
    var lookup = MethodHandles.lookup();
    VarHandle count = lookup.findVarHandle(VarHandleCheck.class, "count", int.class);
    VarHandle total = lookup.findVarHandle(VarHandleCheck.class, "total", long.class);
    VarHandle name = lookup.findVarHandle(VarHandleCheck.class, "name", String.class);
    VarHandle hits = lookup.findStaticVarHandle(VarHandleCheck.class, "hits", int.class);
    VarHandle inherited = lookup.findVarHandle(Sub.class, "inherited", int.class);

    VarHandleCheck o = new VarHandleCheck();
    count.set(o, 5);
    System.out.println((int) count.get(o) + " " + (int) count.getVolatile(o) + " " + o.count);
    System.out.println(count.compareAndSet(o, 5, 6) + " " + count.compareAndSet(o, 5, 9) + " " + o.count);
    System.out.println((int) count.compareAndExchange(o, 6, 8) + " " + (int) count.compareAndExchange(o, 6, 1) + " " + o.count);
    System.out.println((int) count.getAndAdd(o, 2) + " " + (int) count.getAndSet(o, Integer.MAX_VALUE) + " " + o.count);
    System.out.println((int) count.getAndAdd(o, 1) + " " + o.count);
    System.out.println((int) count.getAndBitwiseOr(o, 0xF) + " " + (int) count.getAndBitwiseAnd(o, 0x3C) + " " + (int) count.getAndBitwiseXor(o, 0xFF) + " " + o.count);
    total.setRelease(o, Long.MAX_VALUE);
    System.out.println((long) total.getAndAdd(o, 1L) + " " + (long) total.getAcquire(o));
    System.out.println(name.compareAndSet(o, "a", "b") + " " + (String) name.getAndSet(o, "c") + " " + o.name);
    hits.setVolatile(3);
    System.out.println((int) hits.getAndAdd(4) + " " + VarHandleCheck.hits);
    System.out.println((int) inherited.get(new Sub()));

    int[] xs = {1, 2, 3};
    VarHandle elem = MethodHandles.arrayElementVarHandle(int[].class);
    elem.set(xs, 1, 20);
    System.out.println((int) elem.getAndAdd(xs, 2, 30) + " " + xs[0] + "," + xs[1] + "," + xs[2]);
    try { elem.get(xs, 3); } catch (ArrayIndexOutOfBoundsException e) { System.out.println("aioobe " + e.getMessage()); }

    byte[] buf = new byte[8];
    VarHandle beInt = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    VarHandle leShort = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    VarHandle beDouble = MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.BIG_ENDIAN);
    beInt.set(buf, 0, 0x01020304);
    leShort.set(buf, 4, (short) -2);
    System.out.println(java.util.Arrays.toString(buf) + " " + (int) beInt.get(buf, 0) + " " + (short) leShort.get(buf, 4));
    beDouble.set(buf, 0, 2.5);
    System.out.println((double) beDouble.get(buf, 0) + " " + buf[0] + " " + buf[1]);

    try { lookup.findVarHandle(VarHandleCheck.class, "missing", int.class); }
    catch (NoSuchFieldException e) { System.out.println("NoSuchFieldException " + e.getMessage()); }

    AtomicInteger ai = new AtomicInteger(1);
    AtomicLong al = new AtomicLong(10);
    AtomicBoolean ab = new AtomicBoolean();
    AtomicReference<String> ar = new AtomicReference<>("x");
    System.out.println(ai.incrementAndGet() + " " + ai.getAndAdd(5) + " " + ai.updateAndGet(v -> v * 2) + " " + ai.compareAndSet(14, 0) + " " + ai.get());
    System.out.println(al.addAndGet(5) + " " + ab.compareAndSet(false, true) + " " + ab.get() + " " + ar.getAndSet("y") + ar.get());
    AtomicIntegerArray aa = new AtomicIntegerArray(3);
    aa.addAndGet(2, 9);
    System.out.println(aa);
    ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
    for (String w : "a b a c b a".split(" ")) m.merge(w, 1, Integer::sum);
    System.out.println(new java.util.TreeMap<>(m));
  }
}
