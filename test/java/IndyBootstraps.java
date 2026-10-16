import java.lang.invoke.*;

// Bootstrap methods for IndyCheck, a class written by test/gen/IndyCheckGen.java
// because javac never emits invokedynamic with a bootstrap of its own choosing.
public class IndyBootstraps {
    static int add(int a, int b) { return a + b; }
    static String shout(String s) { return s.toUpperCase() + "!"; }
    static void note(String s) { System.out.println("note: " + s); }

    // the target is the static method named like the call
    public static CallSite byName(MethodHandles.Lookup lookup, String name, MethodType type) throws Throwable {
        return new ConstantCallSite(lookup.findStatic(IndyBootstraps.class, name, type));
    }

    // every kind of static argument, folded into a constant answer
    public static CallSite describe(MethodHandles.Lookup lookup, String name, MethodType type,
                                    String s, int i, long l, float f, double d,
                                    Class<?> c, MethodType mt, MethodHandle h) throws Throwable {
        String text = name + " " + s + " " + i + " " + l + " " + f + " " + d + " " + c.getSimpleName()
            + " " + mt + " " + (int) h.invokeExact(20, 22) + " caller " + lookup.lookupClass().getName();
        return new ConstantCallSite(MethodHandles.dropArguments(MethodHandles.constant(String.class, text), 0, type.parameterArray()));
    }

    // a mutable site whose first call retargets it
    public static CallSite flip(MethodHandles.Lookup lookup, String name, MethodType type) throws Throwable {
        MutableCallSite site = new MutableCallSite(type);
        MethodHandle second = MethodHandles.constant(String.class, "second");
        MethodHandle first = lookup.findStatic(IndyBootstraps.class, "first", MethodType.methodType(String.class, MutableCallSite.class, MethodHandle.class))
            .bindTo(site).bindTo(second);
        site.setTarget(first);
        return site;
    }
    static String first(MutableCallSite site, MethodHandle next) {
        site.setTarget(next);
        return "first";
    }

    // a bootstrap that throws, and one whose call site has the wrong type
    public static CallSite boom(MethodHandles.Lookup lookup, String name, MethodType type) {
        throw new IllegalStateException("boom");
    }
    public static CallSite wrongType(MethodHandles.Lookup lookup, String name, MethodType type) {
        return new ConstantCallSite(MethodHandles.constant(String.class, "wrong"));
    }

    // a dynamic constant
    public static int answer(MethodHandles.Lookup lookup, String name, Class<?> type) {
        return 42;
    }
}
