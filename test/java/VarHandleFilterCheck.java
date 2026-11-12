import java.lang.invoke.*;

// VarHandle combinator filters and checked exceptions, as VarHandles'
// adaptForCheckedExceptions treats them: a direct handle declaring a
// checked exception is refused; an adapted one, whose exceptions aren't
// known, has a checked exception it throws turned into
// IllegalStateException with it as the cause, except under
// filterCoordinates, whose var handle is built from the unadapted filters.
public class VarHandleFilterCheck {
  static int field;
  static int throwsChecked(int x) throws Exception { return x + 1; }
  static int plain(int x) { return x + 1; }
  static int same(int x) { return x; }
  static int unchecked(int x) throws RuntimeException { return x + 1; }
  static int boom(int x, String s) throws Exception {
    if (x == 13) throw new Exception("checked " + s);
    if (x == 14) throw new IllegalArgumentException("unchecked " + s);
    return x;
  }
  static String t(Runnable r) {
    try { r.run(); return "ok " + field; }
    catch (Throwable e) { return e.getClass().getName() + ": " + e.getMessage() + (e.getCause() != null ? " / cause " + e.getCause() : ""); }
  }
  static void row(String name, Object v) { System.out.println(name + ": " + v); }

  public static void main(String[] a) throws Throwable {
    var L = MethodHandles.lookup();
    VarHandle vh = L.findStaticVarHandle(VarHandleFilterCheck.class, "field", int.class);
    MethodHandle chk = L.findStatic(VarHandleFilterCheck.class, "throwsChecked", MethodType.methodType(int.class, int.class));
    MethodHandle pl = L.findStatic(VarHandleFilterCheck.class, "plain", MethodType.methodType(int.class, int.class));
    MethodHandle id = L.findStatic(VarHandleFilterCheck.class, "same", MethodType.methodType(int.class, int.class));
    MethodHandle un = L.findStatic(VarHandleFilterCheck.class, "unchecked", MethodType.methodType(int.class, int.class));
    VarHandle arr = MethodHandles.arrayElementVarHandle(int[].class);

    row("filterValue to declares checked", t(() -> MethodHandles.filterValue(vh, chk, pl)));
    row("filterValue from declares checked", t(() -> MethodHandles.filterValue(vh, pl, chk)));
    row("filterValue declares unchecked", t(() -> MethodHandles.filterValue(vh, un, pl)));
    row("filterCoordinates declares checked", t(() -> MethodHandles.filterCoordinates(arr, 1, chk)));
    row("collectCoordinates declares checked", t(() -> MethodHandles.collectCoordinates(arr, 1, chk)));
    // the screen comes before the other checks
    row("screen before arity", t(() -> MethodHandles.filterValue(vh, chk, MethodHandles.dropArguments(pl, 0, String.class))));
    row("screen before position", t(() -> MethodHandles.collectCoordinates(arr, 9, chk)));
    VarHandle ok = MethodHandles.filterValue(vh, pl, pl);
    ok.set(5);
    row("plain filters", field + " " + (int) ok.get());

    // adapted handles: exceptions unknown, checked ones wrapped when thrown
    MethodHandle b = MethodHandles.insertArguments(L.findStatic(VarHandleFilterCheck.class, "boom", MethodType.methodType(int.class, int.class, String.class)), 1, "v");
    row("adapted declares checked", t(() -> MethodHandles.filterValue(vh, b, id)));
    VarHandle f = MethodHandles.filterValue(vh, b, id);
    row("set 1", t(() -> f.set(1)));
    row("set 13", t(() -> f.set(13)));
    row("set 14", t(() -> f.set(14)));
    VarHandle g = MethodHandles.filterValue(vh, id, b);
    row("get 13", t(() -> { field = 13; int v = (int) g.get(); }));
    row("compareAndSet 13", t(() -> { field = 0; f.compareAndSet(0, 13); }));
    int[] xs = new int[20];
    VarHandle c = MethodHandles.filterCoordinates(arr, 1, b);
    row("filterCoordinates 13", t(() -> c.set(xs, 13, 5)));
    row("filterCoordinates 2", t(() -> { c.set(xs, 2, 5); field = xs[2]; }));
    MethodHandle bc = MethodHandles.insertArguments(L.findStatic(VarHandleFilterCheck.class, "boom", MethodType.methodType(int.class, int.class, String.class)), 1, "c");
    VarHandle d = MethodHandles.collectCoordinates(arr, 1, bc);
    row("collectCoordinates 13", t(() -> d.set(xs, 13, 5)));
    row("collectCoordinates 14", t(() -> d.set(xs, 14, 5)));
  }
}
