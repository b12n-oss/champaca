import java.util.*;
import java.util.function.*;
import java.io.Serializable;

// A lambda's class, one per call site as the JVM spins them: a hidden,
// synthetic, final class named for the class holding the site
// (Caller$$Lambda/0x<n>, the number differs between VMs and isn't printed),
// in its caller's package, module and loader, its caller's nestmate, and not
// found by name. Two sites with the same interface get two classes; one
// site run twice keeps its class.
public class LambdaSiteCheck {
    static String shape(Class<?> c) {
        String n = c.getName();
        return n.substring(0, n.indexOf("/0x") + 3) + "... hidden=" + c.isHidden() + " synthetic=" + c.isSynthetic()
            + " final=" + java.lang.reflect.Modifier.isFinal(c.getModifiers()) + " package=" + c.getPackageName()
            + " simple=" + c.getSimpleName().startsWith(c.getName().substring(c.getName().lastIndexOf('.') + 1));
    }
    static Runnable site() { return () -> {}; }
    public static void main(String[] args) throws Exception {
        Runnable a = () -> {};
        Runnable b = () -> {};
        Supplier<String> s = () -> "x";
        System.out.println("a: " + shape(a.getClass()));
        System.out.println("supplier: " + shape(s.getClass()));
        System.out.println("two sites, two classes: " + (a.getClass() != b.getClass()));
        System.out.println("one site, one class: " + (site().getClass() == site().getClass()));
        System.out.println("loader: " + (a.getClass().getClassLoader() == LambdaSiteCheck.class.getClassLoader()));
        System.out.println("module: " + (a.getClass().getModule() == LambdaSiteCheck.class.getModule()));
        System.out.println("nest host: " + a.getClass().getNestHost().getName());
        System.out.println("interfaces: " + Arrays.toString(s.getClass().getInterfaces()));
        try { Class.forName(a.getClass().getName()); System.out.println("forName: found"); }
        catch (ClassNotFoundException e) { System.out.println("forName: " + e.getClass().getSimpleName()); }
        Function<String, Integer> ref = String::length;
        System.out.println("method ref: " + shape(ref.getClass()));
        Runnable inner = new Object() { Runnable r() { return () -> {}; } }.r();
        System.out.println("in an inner class: " + inner.getClass().getName().startsWith("LambdaSiteCheck$1$$Lambda/0x"));
        Comparator<String> jdk = Comparator.naturalOrder();
        Comparator<String> rev = Comparator.<String>naturalOrder().reversed();
        Comparator<String> by = Comparator.comparing(String::length);
        System.out.println("JDK's: " + by.getClass().getName().startsWith("java.util.Comparator$$Lambda/0x") + " " + by.getClass().getPackageName()
            + " " + by.getClass().getModule().getName() + " " + (by.getClass().getClassLoader() == null) + " " + by.getClass().isHidden());
        Serializable ser = (Runnable & Serializable) () -> {};
        System.out.println("serializable: " + (ser instanceof Serializable) + " " + shape(ser.getClass()));
    }
}
