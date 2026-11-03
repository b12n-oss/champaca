import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.runtime.SwitchBootstraps;

// Class file versions as JDK 25 takes them without --enable-preview: each
// version VersionGen writes, loaded by name, named by an instruction, and
// defined from its bytes with and without a name; a preview class file is
// refused, and so is a switch bootstrap's preview-only label.
public class VersionCheck {
    static final String[] NAMES = {
        "V45_3", "V55_1", "V69_0", "V69_65535", "V68_65535", "V56_65535",
        "V69_1", "V70_0", "V70_65535", "V44_0"};

    interface Body { Object run() throws Throwable; }

    static void row(String label, Body b) {
        String r;
        try {
            Object o = b.run();
            r = o instanceof Class<?> c ? "class " + c.getName() : String.valueOf(o);
        } catch (InvocationTargetException e) {
            r = e.getCause().toString();
        } catch (Throwable t) {
            r = t.toString();
        }
        System.out.println(label + ": " + r);
    }

    static class Definer extends ClassLoader {
        Definer() { super(null); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }

    // vers.Ref's methods name the classes in instructions; VersionGen writes
    // it after javac has run, so it's called through reflection
    static Object ref(String m) throws Throwable {
        return Class.forName("vers.Ref").getMethod(m).invoke(null);
    }

    static byte[] bytes(String n) throws Exception {
        try (InputStream in = VersionCheck.class.getResourceAsStream("/vers/" + n + ".class")) {
            return in.readAllBytes();
        }
    }

    public static void main(String[] args) throws Throwable {
        for (String n : NAMES) {
            row("forName " + n, () -> Class.forName("vers." + n));
        }
        // a second load of a refused class fails the same way again
        row("forName again V70_0", () -> Class.forName("vers.V70_0"));
        row("forName again V69_65535", () -> Class.forName("vers.V69_65535"));
        row("ldc V70_0", () -> ref("newer"));
        row("instanceof V69_65535", () -> ref("preview"));
        row("instanceof V69_65535 again", () -> ref("preview"));
        // refused versions only: an accepted one would be a second definition
        // of a name forName loaded, which champaca's one class table refuses
        for (String n : new String[] {"V69_65535", "V70_0", "V69_1", "V44_0"}) {
            row("define " + n, () -> new Definer().define("vers." + n, bytes(n)));
            row("define unnamed " + n, () -> new Definer().define(null, bytes(n)));
        }
        // the bytes are refused for their version before their name is read
        // PreviewCheck defined these bytes with preview on, through a loader of
        // its own; a run without it refuses them still (they aren't cached)
        row("define D69_65535", () -> new Definer().define("vers.D69_65535", bytes("D69_65535")));
        row("define misnamed V70_0", () -> new Definer().define("vers.Other", bytes("V70_0")));
        row("define misnamed V69_0", () -> new Definer().define("vers.Other", bytes("V69_0")));
        // a long case label is a primitive pattern's, a preview feature
        row("typeSwitch long label", () -> SwitchBootstraps.typeSwitch(MethodHandles.lookup(), "typeSwitch",
                MethodType.methodType(int.class, Object.class, int.class), 1L).getTarget().invoke((Object) 1L, 0));
        row("typeSwitch int label", () -> SwitchBootstraps.typeSwitch(MethodHandles.lookup(), "typeSwitch",
                MethodType.methodType(int.class, Object.class, int.class), 1).getTarget().invoke((Object) 1, 0));
    }
}
