import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;

// A class inheriting two unrelated defaults (DcC, built by
// test/gen/DefaultConflictGen.java) throws IncompatibleClassChangeError at
// every call, through either interface, reflection or a method handle. A
// subclass that overrides works, and a reabstracted default (DcI3) throws
// AbstractMethodError.
public class DefaultConflictCheck {
  interface Thunk { Object call() throws Throwable; }

  static Object make(String n) throws Exception {
    return Class.forName(n).getDeclaredConstructor().newInstance();
  }

  static void t(String label, Thunk f) {
    try { System.out.println(label + " ok " + f.call()); }
    catch (Throwable e) { System.out.println(label + " " + e); }
  }

  public static void main(String[] args) throws Exception {
    DcI1 c = (DcI1) make("DcC");
    t("i1ref", c::m);
    t("i2ref", ((DcI2) c)::m);
    t("again", c::m);
    t("sub", ((DcI1) make("DcSub"))::m);
    t("ame", ((DcI1) make("DcD"))::m);
    t("three", ((DcI1) make("DcT"))::m);
    t("refl", () -> Class.forName("DcC").getMethod("m").invoke(c));
    t("refl-cause", () -> {
      try { return Class.forName("DcC").getMethod("m").invoke(c); }
      catch (InvocationTargetException e) { return "caught " + e.getCause(); }
    });
    t("refl-decl", () -> Class.forName("DcC").getMethod("m").getDeclaringClass());
    t("mh", () -> MethodHandles.lookup()
                  .findVirtual(Class.forName("DcC"), "m", MethodType.methodType(String.class)).invoke(c));
    t("tostr", () -> c.toString().substring(0, 4));
  }
}
