import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;

// getClass() answers the class a literal or Class.forName answers, for every
// kind of class champaca treats apart: JDK, application, anonymous, arrays
// of each depth, a lambda's hidden class, a proxy, a class another loader
// defined. Three passes, so a remembered answer is checked as well.
public class GetClassCheck {
    public static class Local { public Local() {} }

    static void row(String name, Object v) { System.out.println(name + ": " + v); }

    static class Loader extends ClassLoader {
        Loader() { super(GetClassCheck.class.getClassLoader()); }
        Class<?> define(String name) throws Exception {
            byte[] b;
            try (var in = GetClassCheck.class.getResourceAsStream(name + ".class")) { b = in.readAllBytes(); }
            return defineClass(name, b, 0, b.length);
        }
    }

    public static void main(String[] args) throws Exception {
        Object anon = new Object() {};
        Supplier<String> lambda = () -> "x";
        Runnable proxy = (Runnable) Proxy.newProxyInstance(GetClassCheck.class.getClassLoader(), new Class<?>[] { Runnable.class }, (p, m, a) -> null);
        Class<?> other = new Loader().define("GetClassCheck$Local");
        Object otherInstance = other.getDeclaredConstructor().newInstance();
        for (int pass = 0; pass < 3; pass++) {
            String p = " (pass " + pass + ")";
            row("String" + p, "s".getClass() == String.class);
            row("ArrayList" + p, new ArrayList<>().getClass() == Class.forName("java.util.ArrayList"));
            row("application class" + p, new Local().getClass() == Local.class);
            row("anonymous" + p, anon.getClass().getName() + " " + (anon.getClass() == Class.forName(anon.getClass().getName())));
            row("int[]" + p, new int[2].getClass() == int[].class);
            row("String[][]" + p, new String[1][1].getClass() == Class.forName("[[Ljava.lang.String;"));
            row("Local[]" + p, new Local[0].getClass() == Local[].class);
            row("an element of a nested array" + p, (new int[1][2])[0].getClass() == int[].class);
            row("lambda" + p, lambda.getClass() == lambda.getClass() && lambda.getClass().isHidden() && lambda.getClass().getInterfaces()[0] == Supplier.class);
            row("proxy" + p, Proxy.isProxyClass(proxy.getClass()) + " " + (proxy.getClass().getInterfaces()[0] == Runnable.class));
            row("another loader's class" + p, (otherInstance.getClass() == other) + " " + (otherInstance.getClass() != Local.class) + " " + (otherInstance.getClass().getName()));
            row("another loader's array" + p, java.lang.reflect.Array.newInstance(other, 1).getClass().getComponentType() == other);
        }
    }
}
