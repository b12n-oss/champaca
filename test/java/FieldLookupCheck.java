import java.lang.invoke.ConstantBootstraps;
import java.lang.invoke.MethodHandles;

// Fields found through a Lookup (find*Getter/Setter, find*VarHandle) and
// ConstantBootstraps.getStaticFinal as the JDK resolves them: a class's own
// field, then its superinterfaces', then its superclass's; nothing of that
// name and type is NoSuchFieldException with the VM's NoSuchFieldError as
// its cause; static asked of an instance field or the reverse, or a setter
// of a final field, is IllegalAccessException. Access itself isn't checked
// by champaca (limitations.md), so no row reaches a private member of
// another class. A module's identity hash is left out of the texts.
public class FieldLookupCheck {
    interface IF { int IC = 7; }
    interface IG extends IF {}
    static class Base { static final int B = 3; static String S = "s"; static int[] A; int inst; static final int F = 1; }
    static class Sub extends Base implements IG { static int IC = 9; }
    static class Sub2 extends Base implements IG {}
    public static class Pub { public int inst; }

    interface Body { Object run() throws Throwable; }

    static void row(String label, Body b) {
        String r;
        try {
            r = String.valueOf(b.run());
        } catch (Throwable t) {
            r = t + (t.getCause() == null ? "" : ", cause " + t.getCause());
        }
        System.out.println(label + ": " + r.replaceAll("unnamed module @[0-9a-f]+", "unnamed module"));
    }

    public static void main(String[] args) {
        var lk = MethodHandles.lookup();
        row("own over interface", () -> lk.findStaticGetter(Sub.class, "IC", int.class).invoke());
        row("superinterface", () -> lk.findStaticGetter(Sub2.class, "IC", int.class).invoke());
        row("interface itself", () -> lk.findStaticGetter(IG.class, "IC", int.class).invoke());
        row("superclass", () -> lk.findStaticGetter(Sub2.class, "B", int.class).invoke());
        row("var handle superinterface", () -> lk.findStaticVarHandle(Sub2.class, "IC", int.class).get());
        row("missing int", () -> lk.findStaticGetter(Base.class, "Q", int.class));
        row("missing String", () -> lk.findStaticGetter(Base.class, "Q", String.class));
        row("missing int[]", () -> lk.findStaticGetter(Base.class, "Q", int[].class));
        row("missing Object[][]", () -> lk.findStaticGetter(Base.class, "Q", Object[][].class));
        row("wrong type", () -> lk.findStaticGetter(Base.class, "B", long.class));
        row("missing static setter", () -> lk.findStaticSetter(Base.class, "Q", int.class));
        row("missing getter", () -> lk.findGetter(Base.class, "Q", int.class));
        row("missing setter", () -> lk.findSetter(Base.class, "Q", int.class));
        row("missing var handle", () -> lk.findVarHandle(Base.class, "Q", int.class));
        row("instance through static getter", () -> lk.findStaticGetter(Base.class, "inst", int.class));
        row("instance through static setter", () -> lk.findStaticSetter(Base.class, "inst", int.class));
        row("static through getter", () -> lk.findGetter(Base.class, "S", String.class));
        row("static through setter", () -> lk.findSetter(Base.class, "S", String.class));
        row("static through var handle", () -> lk.findVarHandle(Base.class, "S", String.class));
        row("final static setter", () -> lk.findStaticSetter(Base.class, "F", int.class));
        row("public lookup", () -> MethodHandles.publicLookup().findStaticGetter(Pub.class, "inst", int.class));
        row("getStaticFinal superinterface", () -> ConstantBootstraps.getStaticFinal(lk, "IC", int.class, Sub2.class));
        row("getStaticFinal instance", () -> ConstantBootstraps.getStaticFinal(lk, "inst", int.class, Base.class));
        row("getStaticFinal missing", () -> ConstantBootstraps.getStaticFinal(lk, "NOPE", int.class, Base.class));
        row("getStaticFinal not final", () -> ConstantBootstraps.getStaticFinal(lk, "S", String.class, Base.class));
        row("getStaticFinal own", () -> ConstantBootstraps.getStaticFinal(lk, "B", int.class, Base.class));
    }
}
