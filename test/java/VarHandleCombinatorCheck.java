import java.lang.invoke.*;
import java.lang.invoke.VarHandle.AccessMode;
import java.util.List;

public class VarHandleCombinatorCheck {
  int n; String s = "a";
  static int stat;
  static int[] arr = {10, 20, 30};

  static String tryIt(Runnable r) {
    try { r.run(); return "ok"; } catch (Throwable t) { return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()); }
  }
  static int neg(int x) { return -x; }
  static int unneg(int x) { return -x; }
  static String wrap(int x) { return "<" + x + ">"; }
  static int unwrap(String s) { return Integer.parseInt(s.substring(1, s.length() - 1)); }
  static int idx(String key) { return key.length(); }
  static VarHandleCombinatorCheck pick(VarHandleCombinatorCheck a, VarHandleCombinatorCheck b, boolean first) { return first ? a : b; }
  static int sideSum;
  static void side(int x) { sideSum += x; }
  static int shift(int base, int x) { return x - base; }
  static int unshift(int base, int x) { return x + base; }

  public static void main(String[] a) throws Throwable {
    var lk = MethodHandles.lookup();
    VarHandle N = lk.findVarHandle(VarHandleCombinatorCheck.class, "n", int.class);
    VarHandle A = MethodHandles.arrayElementVarHandle(int[].class);
    VarHandleCombinatorCheck o = new VarHandleCombinatorCheck();
    MethodType i2i = MethodType.methodType(int.class, int.class);
    MethodHandle NEG = lk.findStatic(VarHandleCombinatorCheck.class, "neg", i2i);
    MethodHandle UNNEG = lk.findStatic(VarHandleCombinatorCheck.class, "unneg", i2i);
    MethodHandle WRAP = lk.findStatic(VarHandleCombinatorCheck.class, "wrap", MethodType.methodType(String.class, int.class));
    MethodHandle UNWRAP = lk.findStatic(VarHandleCombinatorCheck.class, "unwrap", MethodType.methodType(int.class, String.class));
    MethodHandle IDX = lk.findStatic(VarHandleCombinatorCheck.class, "idx", MethodType.methodType(int.class, String.class));
    MethodHandle PICK = lk.findStatic(VarHandleCombinatorCheck.class, "pick", MethodType.methodType(VarHandleCombinatorCheck.class, VarHandleCombinatorCheck.class, VarHandleCombinatorCheck.class, boolean.class));

    // filterValue: a String view of an int variable
    VarHandle FV = MethodHandles.filterValue(N, UNWRAP, WRAP);
    FV.set(o, "<5>");
    System.out.println((String) FV.get(o) + " " + o.n + " " + FV.varType().getName() + " " + FV.coordinateTypes());
    System.out.println(FV.compareAndSet(o, "<5>", "<6>") + " " + o.n + " " + (String) FV.getAndSet(o, "<7>") + " " + o.n);
    System.out.println((String) FV.compareAndExchange(o, "<7>", "<8>") + " " + o.n);
    System.out.println(FV.compareAndSet(o, "<555>", "<6>") + " " + FV.weakCompareAndSet(o, "<555>", "<6>") + " " + o.n);
    System.out.println("fv add " + tryIt(() -> FV.getAndAdd(o, "<1>")) + " fv or " + tryIt(() -> FV.getAndBitwiseOr(o, "<1>")));
    StringBuilder sb = new StringBuilder();
    for (AccessMode m : AccessMode.values()) sb.append(FV.isAccessModeSupported(m) ? '1' : '0');
    System.out.println(sb + " " + FV.accessModeType(AccessMode.GET_AND_ADD));
    // filterValue with a value-only transform (negate), still int
    VarHandle NEGV = MethodHandles.filterValue(N, NEG, UNNEG);
    NEGV.set(o, 5);
    System.out.println((int) NEGV.get(o) + " " + o.n + " " + (int) NEGV.getAndAdd(o, 3) + " " + o.n + " " + (int) NEGV.get(o));

    // insertCoordinates
    VarHandle B = MethodHandles.insertCoordinates(N, 0, o);
    B.set(9);
    System.out.println((int) B.get() + " " + B.coordinateTypes() + " " + B.varType().getName());
    VarHandle E1 = MethodHandles.insertCoordinates(A, 0, arr, 1);
    System.out.println((int) E1.getAndAdd(2) + " " + arr[1]);

    // dropCoordinates
    VarHandle D = MethodHandles.dropCoordinates(N, 1, String.class, long.class);
    D.set(o, "ignored", 1L, 42);
    System.out.println(o.n + " " + D.coordinateTypes());
    VarHandle D0 = MethodHandles.dropCoordinates(N, 0, String.class);
    D0.set("x", o, 41);
    System.out.println(o.n + " " + D0.coordinateTypes());

    // filterCoordinates: address an int[] element by a String key's length
    VarHandle FC = MethodHandles.filterCoordinates(A, 1, IDX);
    FC.set(arr, "ab", 7);
    System.out.println(arr[2] + " " + FC.coordinateTypes());

    // permuteCoordinates: swap the two coords of an array handle
    VarHandle P = MethodHandles.permuteCoordinates(A, List.of(int.class, int[].class), 1, 0);
    P.set(0, arr, 99);
    System.out.println(arr[0] + " " + P.coordinateTypes());

    // collectCoordinates: two objects and a boolean collapse to one receiver
    VarHandle C = MethodHandles.collectCoordinates(N, 0, PICK);
    VarHandleCombinatorCheck p = new VarHandleCombinatorCheck(); VarHandleCombinatorCheck q = new VarHandleCombinatorCheck();
    C.set(p, q, true, 1); C.set(p, q, false, 2);
    System.out.println(p.n + " " + q.n + " " + C.coordinateTypes());

    // stacking: insert over filterValue
    VarHandle S2 = MethodHandles.insertCoordinates(FV, 0, o);
    System.out.println((String) S2.get() + " " + S2.coordinateTypes() + " " + S2.varType().getName());

    // a void filter runs for its effect and keeps the coordinate
    MethodHandle SIDE = lk.findStatic(VarHandleCombinatorCheck.class, "side", MethodType.methodType(void.class, int.class));
    VarHandle CV = MethodHandles.collectCoordinates(A, 1, SIDE);
    CV.set(arr, 3, 1, 8);
    System.out.println(sideSum + " " + arr[1] + " " + CV.coordinateTypes());

    // primitive coordinate values widen but never narrow, and nulls refuse
    System.out.println(tryIt(() -> MethodHandles.insertCoordinates(A, 1, (short) 1)) + " "
        + tryIt(() -> MethodHandles.insertCoordinates(A, 1, 1L)) + " "
        + tryIt(() -> MethodHandles.insertCoordinates(A, 1, (Object) null)));
    System.out.println(tryIt(() -> MethodHandles.permuteCoordinates(A, List.of(int.class, int[].class), 0))
        + " | " + tryIt(() -> MethodHandles.permuteCoordinates(A, List.of(int.class, int[].class), 1, 2)));

    // construction errors
    System.out.println(tryIt(() -> MethodHandles.insertCoordinates(N, 2, o)));
    System.out.println(tryIt(() -> MethodHandles.insertCoordinates(N, 0, "notVarHandleCombinatorCheck")));
    System.out.println(tryIt(() -> MethodHandles.insertCoordinates(N, 0, o, o)));
    System.out.println(tryIt(() -> MethodHandles.filterValue(N, WRAP, UNWRAP)));
    System.out.println(tryIt(() -> MethodHandles.filterValue(N, UNWRAP, null)));
    System.out.println(tryIt(() -> MethodHandles.filterCoordinates(A, 1, WRAP)));
    System.out.println(tryIt(() -> MethodHandles.dropCoordinates(N, 3, String.class)));
    System.out.println(tryIt(() -> MethodHandles.permuteCoordinates(A, List.of(int.class, int[].class), 0, 0)));
    System.out.println(tryIt(() -> MethodHandles.collectCoordinates(N, 0, WRAP)));
    // exact behavior carries over
    System.out.println(FV.withInvokeExactBehavior().hasInvokeExactBehavior() + " " + tryIt(() -> { Object x = FV.withInvokeExactBehavior().get(o); }));
    // a derived handle over a static field, and null into a reference coordinate
    VarHandle S0 = lk.findStaticVarHandle(VarHandleCombinatorCheck.class, "stat", int.class);
    VarHandle SD = MethodHandles.dropCoordinates(S0, 0, String.class);
    SD.set((String) null, 3);
    System.out.println(stat + " " + SD.coordinateTypes() + " " + tryIt(() -> MethodHandles.insertCoordinates(N, 0, (Object) null)));

    // over a byte[] view, the plain-only rule survives
    VarHandle V = MethodHandles.byteArrayViewVarHandle(int[].class, java.nio.ByteOrder.BIG_ENDIAN);
    VarHandle VI = MethodHandles.insertCoordinates(V, 1, 0);
    byte[] buf = new byte[8];
    VI.set(buf, 0x01020304);
    System.out.println((int) VI.get(buf) + " " + buf[0] + " " + tryIt(() -> VI.getVolatile(buf)) + " " + VI.coordinateTypes());

    // permute duplicating one coordinate
    VarHandle DUP = MethodHandles.permuteCoordinates(N, List.of(VarHandleCombinatorCheck.class, VarHandleCombinatorCheck.class), 0);
    DUP.set(o, o, 55);
    System.out.println(o.n + " " + DUP.coordinateTypes());

    // filters with an extra argument: the value transform sees a context coordinate
    MethodHandle SHIFT = lk.findStatic(VarHandleCombinatorCheck.class, "shift", MethodType.methodType(int.class, int.class, int.class));
    MethodHandle UNSHIFT = lk.findStatic(VarHandleCombinatorCheck.class, "unshift", MethodType.methodType(int.class, int.class, int.class));
    VarHandle SH = MethodHandles.filterValue(N, SHIFT, UNSHIFT);
    SH.set(o, 100, 7);
    System.out.println(o.n + " " + (int) SH.get(o, 100) + " " + SH.coordinateTypes() + " " + SH.varType().getName());

    // three combinators stacked
    VarHandle STACK = MethodHandles.insertCoordinates(MethodHandles.permuteCoordinates(
        MethodHandles.filterCoordinates(A, 1, IDX), List.of(String.class, int[].class), 1, 0), 1, arr);
    STACK.set("ab", -1);
    System.out.println(arr[2] + " " + java.util.Arrays.toString(arr) + " " + STACK.coordinateTypes());

    // boxing at the site of a derived handle
    Object boxed = NEGV.get(o);
    System.out.println(boxed.getClass().getSimpleName() + " " + boxed);
  }
}
