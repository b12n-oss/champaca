import java.lang.invoke.*;
import java.util.*;
// MethodHandles.Lookup's find*, unreflect* and findVarHandle check access as
// the JVM's do, with its texts: the class named, then a method HotSpot's
// resolution can't reach ("no such method"), then the member's modifiers
// against the lookup ("member is private: ..."); a public lookup, findSpecial's
// caller, nestmates, and a protected method's receiver narrowed to the
// lookup class. Identity hashes are masked.
public class LookupAccessCheck {
  static class Nest { private int p = 1; private static int sp() { return 2; } private Nest() {} }
  static class Sub extends AbstractList<Integer> {
    public Integer get(int i) { return i; }
    public int size() { return 3; }
    static Object range() throws Throwable {
      MethodHandle h = MethodHandles.lookup().findVirtual(AbstractList.class, "removeRange", MethodType.methodType(void.class, int.class, int.class));
      return h.type();
    }
  }
  public static int answer() { return 42; }
  interface Run { Object run() throws Throwable; }
  static void row(String n, Run r) { String o; try { o = String.valueOf(r.run()); } catch (Throwable t) { o = t.getClass().getName() + ": " + t.getMessage(); } System.out.println(n + " = " + o.replaceAll("@[0-9a-f]+", "@X")); }
  public static void main(String[] a) {
    MethodHandles.Lookup l = MethodHandles.lookup();
    MethodHandles.Lookup pub = MethodHandles.publicLookup();
    row("findVirtual private String.coder", () -> l.findVirtual(String.class, "coder", MethodType.methodType(byte.class)));
    row("findStatic private Integer", () -> l.findStatic(Integer.class, "stringSize", MethodType.methodType(int.class, int.class)));
    row("findGetter private Integer.value", () -> l.findGetter(Integer.class, "value", int.class));
    row("findStaticGetter package String.COMPACT_STRINGS", () -> l.findStaticGetter(String.class, "COMPACT_STRINGS", boolean.class));
    row("findConstructor private", () -> l.findConstructor(Optional.class, MethodType.methodType(void.class, Object.class)));
    row("findVirtual public", () -> l.findVirtual(String.class, "length", MethodType.methodType(int.class)) != null);
    row("nestmate private field", () -> l.findGetter(Nest.class, "p", int.class) != null);
    row("nestmate private static", () -> l.findStatic(Nest.class, "sp", MethodType.methodType(int.class)) != null);
    row("publicLookup nestmate private", () -> pub.findStatic(Nest.class, "sp", MethodType.methodType(int.class)));
    row("publicLookup ArrayList.size field", () -> pub.findGetter(ArrayList.class, "size", int.class));
    row("publicLookup public method", () -> pub.findStatic(LookupAccessCheck.class, "answer", MethodType.methodType(int.class)).invoke());
    row("bind private", () -> l.bind("str", "coder", MethodType.methodType(byte.class)));
    row("bind public", () -> l.bind("str", "length", MethodType.methodType(int.class)).invoke());
    row("bind nestmate private", () -> l.bind(new Nest(), "toString", MethodType.methodType(String.class)) != null);
    row("findSpecial other class", () -> l.findSpecial(Object.class, "toString", MethodType.methodType(String.class), String.class));
    row("unreflect private", () -> l.unreflect(String.class.getDeclaredMethod("coder")));
    row("unreflectGetter private", () -> l.unreflectGetter(Integer.class.getDeclaredField("value")));
    row("findVarHandle private", () -> l.findVarHandle(Integer.class, "value", int.class));
    row("protected Object.clone", () -> l.findVirtual(Object.class, "clone", MethodType.methodType(Object.class)));
    row("protected from a subclass", () -> Sub.range());
    row("array clone", () -> { MethodHandle h = l.findVirtual(int[].class, "clone", MethodType.methodType(Object.class)); return h.type() + " " + ((int[]) h.invoke(new int[]{4, 5}))[1]; });
    row("privateLookupIn nestmate", () -> MethodHandles.privateLookupIn(Nest.class, l).findGetter(Nest.class, "p", int.class).type());
    row("narrowed clone called", () -> { MethodHandle h = l.findVirtual(Object.class, "clone", MethodType.methodType(Object.class)); try { return h.invoke(new LookupAccessCheck()); } catch (Throwable t) { return t.getClass().getName(); } });
  }
}
