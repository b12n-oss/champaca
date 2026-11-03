import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.runtime.SwitchBootstraps;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Supplier;

// JDK 25's preview features under --enable-preview (PreviewCheck.options):
// this class file is 69.65535, as javac --enable-preview writes it.
// Structured concurrency, stable values, primitive types in patterns
// (switches whose case labels are primitive classes, longs, floats,
// doubles or booleans, which run the JDK's own SwitchBootstraps), and the
// class file versions preview enables or still refuses.
public class PreviewCheck {
    interface Body { Object run() throws Throwable; }

    static void row(String label, Body b) {
        String r;
        try {
            Object o = b.run();
            r = o instanceof Class<?> c ? "class " + c.getName() : String.valueOf(o);
        } catch (Throwable t) {
            r = t.toString();
        }
        System.out.println(label + ": " + r);
    }

    static class Definer extends ClassLoader {
        Definer() { super(null); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }

    static byte[] bytes(String n) throws Exception {
        try (InputStream in = PreviewCheck.class.getResourceAsStream("/vers/" + n + ".class")) {
            return in.readAllBytes();
        }
    }

    static final StableValue<String> SV = StableValue.of();
    static int computed;
    static final Supplier<Integer> LAZY = StableValue.supplier(() -> ++computed * 10);

    static String onLong(long v) {
        return switch (v) {
            case 1L -> "one";
            case 10_000_000_000L -> "big";
            case long x when x < 0 -> "neg " + x;
            default -> "other";
        };
    }
    static String onDouble(double d) { return switch (d) { case 0.5 -> "half"; case double x -> "d " + x; }; }
    static String onFloat(float f) { return switch (f) { case 1.5f -> "f1.5"; case float x -> "f " + x; }; }
    static String onBoolean(boolean b) { return switch (b) { case true -> "yes"; case false -> "no"; }; }
    static String onInt(int i) {
        return switch (i) {
            case byte b -> "byte " + b;
            case short s -> "short " + s;
            case char c -> "char " + (int) c;
            case int x -> "int " + x;
        };
    }
    static String onObject(Object o) {
        return switch (o) {
            case Integer i when i > 5 -> "big Integer";
            case int i -> "int " + i;
            case Long l -> "Long " + l;
            case double d -> "double " + d;
            default -> "other " + o;
        };
    }
    // a primitive selector whose labels champaca could match itself (an int,
    // a class): the JDK's bootstrap still decides, since the selector is raw
    static String onIntBoxing(int v) {
        return switch (v) { case 1 -> "one"; case Integer i when i > 10 -> "big " + i; case Integer i -> "Integer " + i; };
    }
    static String onBoxed(Integer i) {
        return switch (i) { case float f -> "float " + f; case Integer x -> "Integer " + x; };
    }

    public static void main(String[] args) throws Throwable {
        row("stable unset", () -> SV.isSet());
        row("stable set", () -> SV.orElseSet(() -> "first") + " " + SV.orElseSet(() -> "second"));
        row("stable trySet", () -> SV.trySet("third") + " " + SV.orElseThrow());
        row("stable supplier", () -> LAZY.get() + " " + LAZY.get() + " computed " + computed);
        row("scope join", () -> {
            try (var scope = StructuredTaskScope.open()) {
                var x = scope.fork(() -> 20);
                var y = scope.fork(() -> 22);
                scope.join();
                return x.get() + y.get() + " " + x.state();
            }
        });
        row("scope failure", () -> {
            try (var scope = StructuredTaskScope.open()) {
                scope.fork(() -> { throw new IllegalStateException("boom"); });
                var slow = scope.fork(() -> { Thread.sleep(60_000); return 1; });
                try {
                    scope.join();
                    return "joined";
                } catch (StructuredTaskScope.FailedException e) {
                    return "failed " + e.getCause() + ", other " + slow.state();
                }
            }
        });
        row("scope any", () -> {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>anySuccessfulResultOrThrow())) {
                scope.fork(() -> { throw new IllegalStateException("no"); });
                scope.fork(() -> "yes");
                return scope.join();
            }
        });
        row("long switch", () -> onLong(1) + " " + onLong(10_000_000_000L) + " " + onLong(-3) + " " + onLong(7));
        row("double switch", () -> onDouble(0.5) + " " + onDouble(-0.0) + " " + onDouble(Double.NaN));
        row("float switch", () -> onFloat(1.5f) + " " + onFloat(2f));
        row("boolean switch", () -> onBoolean(true) + " " + onBoolean(false));
        row("int switch", () -> onInt(100) + ", " + onInt(-129) + ", " + onInt(40000) + ", " + onInt(70000));
        row("Object switch", () -> onObject(9) + ", " + onObject(2) + ", " + onObject(4L) + ", " + onObject(2.5) + ", "
                + onObject(2.5f) + ", " + onObject((short) 3) + ", " + onObject('c'));
        row("int switch to Integer", () -> onIntBoxing(1) + ", " + onIntBoxing(20) + ", " + onIntBoxing(3));
        row("Integer switch", () -> onBoxed(100) + ", " + onBoxed(16_777_216) + ", " + onBoxed(16_777_217));
        int big = 300; long l = 1L << 40; double three = 3.0; double frac = 3.5; long exact = 1L << 53;
        row("instanceof", () -> (big instanceof byte) + " " + (100 instanceof byte b ? "b" + b : "no") + " "
                + (l instanceof int) + " " + (three instanceof int i ? "i" + i : "no") + " " + (frac instanceof int) + " "
                + (65 instanceof char c ? "c" + c : "no") + " " + (exact instanceof double) + " " + ((exact + 1) instanceof double) + " "
                + (-0.0 instanceof int) + " " + (Double.NaN instanceof float));
        Object boxed = 42;
        row("instanceof unboxing", () -> (boxed instanceof int n ? "int " + n : "no") + " " + (boxed instanceof long) + " "
                + (boxed instanceof double));
        row("forName V69_65535", () -> Class.forName("vers.V69_65535"));
        // VersionCheck defines the same bytes without preview, which refuses them
        row("define D69_65535", () -> new Definer().define("vers.D69_65535", bytes("D69_65535")));
        // VersionCheck makes it again without preview, which refuses the interface
        row("lambda over PI", () -> Class.forName("vers.LamUser").getMethod("make").invoke(null));
        row("forName V68_65535", () -> Class.forName("vers.V68_65535"));
        row("typeSwitch long label", () -> SwitchBootstraps.typeSwitch(MethodHandles.lookup(), "typeSwitch",
                MethodType.methodType(int.class, Object.class, int.class), 1L).getTarget().invoke((Object) 1L, 0));
    }
}
