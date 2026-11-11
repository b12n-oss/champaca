import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

// What reflection sees on a lambda's class: its declared methods (the
// interface's abstract method, public), invoking one through Method.invoke,
// getMethod finding it, and the class's own modifiers. Names of lambda
// classes and their fields (a capture, or champaca's closure slot) differ
// between VMs, so neither is printed.
public class LambdaReflectCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String l, Callable c) { try { System.out.println(l + ": " + c.call()); } catch (Throwable e) { System.out.println(l + ": " + e); } }
    interface Shout { String shout(String s); default String twice(String s) { return shout(s) + shout(s); } }
    static List<String> declared(Object o) {
        List<String> ns = new ArrayList<>();
        for (Method m : o.getClass().getDeclaredMethods()) ns.add(Modifier.toString(m.getModifiers()) + " " + m.getReturnType().getSimpleName() + " " + m.getName() + Arrays.toString(m.getParameterTypes()));
        Collections.sort(ns);
        return ns;
    }
    public static void main(String[] args) {
        Supplier<String> sup = () -> "x";
        Function<String, Integer> len = String::length;
        Runnable run = () -> {};
        Shout shout = s -> s.toUpperCase();
        BiFunction<Integer, Integer, Integer> add = Integer::sum;
        t("supplier", () -> declared(sup));
        t("function", () -> declared(len));
        t("runnable", () -> declared(run));
        t("own interface", () -> declared(shout));
        t("bifunction", () -> declared(add));
        t("invoke", () -> { Method m = sup.getClass().getDeclaredMethod("get"); m.setAccessible(true); return m.invoke(sup); });
        t("invoke function", () -> { Method m = len.getClass().getDeclaredMethod("apply", Object.class); m.setAccessible(true); return m.invoke(len, "four"); });
        t("getMethod", () -> { Method m = shout.getClass().getMethod("shout", String.class); m.setAccessible(true); return m.invoke(shout, "hi"); });
        t("default via getMethod", () -> { Method m = shout.getClass().getMethod("twice", String.class); return m.getDeclaringClass().getSimpleName() + " " + m.invoke(shout, "a"); });
        t("synthetic", () -> sup.getClass().isSynthetic());
        t("final", () -> Modifier.isFinal(sup.getClass().getModifiers()));
        t("interfaces", () -> Arrays.toString(shout.getClass().getInterfaces()));
    }
}
