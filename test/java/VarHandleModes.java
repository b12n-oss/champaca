import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.invoke.VarHandle.AccessMode;
import java.nio.ByteOrder;
import java.util.Arrays;

// Which access modes each kind of VarHandle supports, what an unsupported
// one throws, and the handle's own methods (varType, accessModeType, ...).
public class VarHandleModes {
  byte b; char c; short s; float f; double d; boolean z; String str = "a";
  final int fin = 3;
  static final int SFIN = 4;

  static String tryIt(Runnable r) {
    try { r.run(); return "ok"; }
    catch (Throwable t) { return t.getClass().getSimpleName(); }
  }

  public static void main(String[] args) throws Throwable {
    var lk = MethodHandles.lookup();
    VarHandle B = lk.findVarHandle(VarHandleModes.class, "b", byte.class);
    VarHandle C = lk.findVarHandle(VarHandleModes.class, "c", char.class);
    VarHandle S = lk.findVarHandle(VarHandleModes.class, "s", short.class);
    VarHandle F = lk.findVarHandle(VarHandleModes.class, "f", float.class);
    VarHandle D = lk.findVarHandle(VarHandleModes.class, "d", double.class);
    VarHandle Z = lk.findVarHandle(VarHandleModes.class, "z", boolean.class);
    VarHandle STR = lk.findVarHandle(VarHandleModes.class, "str", String.class);
    VarHandle FIN = lk.findVarHandle(VarHandleModes.class, "fin", int.class);
    VarHandle SFIN = lk.findStaticVarHandle(VarHandleModes.class, "SFIN", int.class);
    VarHandleModes o = new VarHandleModes();

    // narrow types wrap on numeric updates
    B.set(o, (byte) 127);
    System.out.println((byte) B.getAndAdd(o, (byte) 1) + " " + o.b);
    C.set(o, (char) 0xFFFF);
    System.out.println((int) (char) C.getAndAdd(o, (char) 2) + " " + (int) o.c);
    S.set(o, (short) -32768);
    System.out.println((short) S.getAndAdd(o, (short) -1) + " " + o.s + " " + (short) S.getAndBitwiseXor(o, (short) -1) + " " + o.s);

    // float adds round to float
    F.set(o, 0.1f);
    System.out.println((float) F.getAndAdd(o, 0.2f) + " " + o.f + " " + (o.f == 0.1f + 0.2f));
    D.set(o, Double.NaN);
    System.out.println(D.compareAndSet(o, Double.NaN, 1.0) + " " + o.d);
    D.set(o, 0.0);
    System.out.println(D.compareAndSet(o, -0.0, 1.0) + " " + o.d);
    System.out.println(Z.compareAndSet(o, false, true) + " " + o.z + " " + (boolean) Z.getAndBitwiseXor(o, true) + " " + o.z);

    // unsupported modes
    System.out.println("float or " + tryIt(() -> F.getAndBitwiseOr(o, 1f)));
    System.out.println("double and " + tryIt(() -> D.getAndBitwiseAnd(o, 1.0)));
    System.out.println("boolean add " + tryIt(() -> Z.getAndAdd(o, true)));
    System.out.println("ref add " + tryIt(() -> STR.getAndAdd(o, "x")));
    System.out.println("ref xor " + tryIt(() -> STR.getAndBitwiseXor(o, "x")));
    System.out.println("final get " + (int) FIN.get(o) + " set " + tryIt(() -> FIN.set(o, 9)) + " cas " + tryIt(() -> FIN.compareAndSet(o, 3, 9)) + " " + o.fin);
    System.out.println("static final get " + (int) SFIN.getVolatile() + " set " + tryIt(() -> SFIN.setVolatile(9)));

    // what each handle says it supports
    for (VarHandle h : new VarHandle[] {B, F, Z, STR, FIN}) {
      StringBuilder sb = new StringBuilder();
      for (AccessMode m : AccessMode.values()) sb.append(h.isAccessModeSupported(m) ? '1' : '0');
      System.out.println(h.varType().getName() + " " + h.coordinateTypes() + " " + sb);
    }
    System.out.println(STR.accessModeType(AccessMode.COMPARE_AND_SET));
    System.out.println(STR.accessModeType(AccessMode.GET_AND_SET));
    System.out.println(SFIN.accessModeType(AccessMode.GET));
    System.out.println(AccessMode.valueFromMethodName("getAndAddAcquire") + " " + AccessMode.GET_OPAQUE.methodName());
    try { F.getAndBitwiseOr(o, 1f); } catch (UnsupportedOperationException e) { System.out.println("uoe " + e.getMessage()); }
    try { FIN.setVolatile(o, 1); } catch (UnsupportedOperationException e) { System.out.println("uoe " + e.getMessage()); }
    try { F.getAndBitwiseOr((VarHandleModes) null, 1f); } catch (Exception e) { System.out.println(e.getClass().getSimpleName()); }
    System.out.println(F + " " + STR.hasInvokeExactBehavior() + " " + STR.withInvokeExactBehavior().hasInvokeExactBehavior());
    // the call site's types are adapted to the handle's, as asType would
    F.set(o, 3);
    Object boxed = B.get(o);
    long widened = (long) B.get(o) + 0L;
    Object flag = Z.compareAndSet(o, false, false);
    System.out.println(o.f + " " + (o.f + 0.5f) + " " + boxed.getClass().getSimpleName() + " " + boxed + " " + widened + " " + flag.getClass().getSimpleName());
    Integer seven = 7;
    S.set(o, (short) 1);
    System.out.println((short) S.getAndAdd(o, (short) 2) + " " + D.compareAndSet(o, 0.0, 5) + " " + o.d);
    VarHandle EX = B.withInvokeExactBehavior();
    System.out.println((byte) EX.get(o) + " exact boxed " + tryIt(() -> { Object x = EX.get(o); }));
    try { Object x = EX.get(o); } catch (Throwable t) { System.out.println(t); }
    try { Object x = FIN.get(o, 1); } catch (Throwable t) { System.out.println(t.getClass().getSimpleName()); }
    System.out.println("null receiver " + tryIt(() -> STR.get((VarHandleModes) null)));

    // through a MethodHandle
    var mh = STR.toMethodHandle(AccessMode.GET);
    System.out.println(mh.type() + " " + (String) mh.invoke(o));
    var mh2 = B.toMethodHandle(AccessMode.GET_AND_ADD);
    System.out.println((byte) mh2.invoke(o, (byte) 5) + " " + o.b);

    // from reflection
    VarHandle RS = lk.unreflectVarHandle(VarHandleModes.class.getDeclaredField("str"));
    VarHandle RF = lk.unreflectVarHandle(VarHandleModes.class.getDeclaredField("SFIN"));
    System.out.println((String) RS.getAndSet(o, "r") + " " + o.str + " " + RS + " " + (int) RF.get() + " " + tryIt(() -> RF.set(1)));

    // arrays
    Object[] objs = new String[2];
    VarHandle OA = MethodHandles.arrayElementVarHandle(Object[].class);
    System.out.println("array store " + tryIt(() -> OA.set(objs, 0, Integer.valueOf(1))));
    for (Runnable r : new Runnable[] {() -> OA.set(objs, 0, 1), () -> OA.compareAndSet(objs, 0, null, 1),
                                      () -> OA.getAndSet(objs, 0, 1), () -> OA.set(new Integer[1][], 0, new String[0])})
      try { r.run(); } catch (ArrayStoreException e) { System.out.println("ase " + e.getMessage()); }
    OA.set(objs, 1, "s");
    OA.set(objs, 0, null);
    System.out.println(objs[0] + " " + objs[1] + " " + OA.compareAndSet(objs, 1, "s", "t") + " " + objs[1]);
    char[] cs = {'a', 0xFFFF};
    VarHandle CA = MethodHandles.arrayElementVarHandle(char[].class);
    System.out.println((int) (char) CA.getAndAdd(cs, 1, (char) 1) + " " + (int) cs[1]);
    boolean[] bs = {false};
    VarHandle BA = MethodHandles.arrayElementVarHandle(boolean[].class);
    System.out.println("boolean[] add " + tryIt(() -> BA.getAndAdd(bs, 0, true)) + " or " + (boolean) BA.getAndBitwiseOr(bs, 0, true) + " " + bs[0]);
    System.out.println(CA.varType().getName() + " " + CA.coordinateTypes());

    // byte[] views: alignment and supported modes
    byte[] buf = new byte[16];
    VarHandle VI = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    VarHandle VS = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    VarHandle VF = MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.BIG_ENDIAN);
    System.out.println("plain misaligned " + tryIt(() -> VI.set(buf, 1, 7)) + " " + (int) VI.get(buf, 1));
    System.out.println("volatile misaligned " + tryIt(() -> VI.getVolatile(buf, 1)));
    System.out.println("cas misaligned " + tryIt(() -> VI.compareAndSet(buf, 1, 7, 8)));
    System.out.println("cas aligned " + tryIt(() -> VI.compareAndSet(buf, 8, 0, 8)) + " add aligned " + tryIt(() -> VI.getAndAdd(buf, 8, 1)));
    System.out.println("short cas " + tryIt(() -> VS.compareAndSet(buf, 0, (short) 0, (short) 1)) + " short volatile " + tryIt(() -> VS.getVolatile(buf, 0)));
    System.out.println("float add " + tryIt(() -> VF.getAndAdd(buf, 4, 1f)) + " float cas " + tryIt(() -> VF.compareAndSet(buf, 4, 0f, 1f)));
    System.out.println("view bounds " + tryIt(() -> VI.get(buf, 13)));
    StringBuilder sb = new StringBuilder();
    for (VarHandle h : new VarHandle[] {VI, VS, VF})
      for (AccessMode m : AccessMode.values()) sb.append(h.isAccessModeSupported(m) ? '1' : '0');
    System.out.println(sb);
    System.out.println(VI.varType().getName() + " " + VI.coordinateTypes());
    try { VI.getVolatile(buf, 0); } catch (UnsupportedOperationException e) { System.out.println("uoe " + e.getMessage()); }
    var bad = VI.toMethodHandle(AccessMode.GET_AND_ADD);
    try { bad.invoke(buf, 0, 1); } catch (UnsupportedOperationException e) { System.out.println("mh uoe " + e.getMessage() + " " + bad.type()); }
  }
}
