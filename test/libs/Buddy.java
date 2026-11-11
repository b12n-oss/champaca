import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.*;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import java.lang.reflect.*;
import java.util.concurrent.Callable;
import java.util.function.*;

// Byte Buddy: classes generated at run time and loaded by each strategy,
// fixed values, delegation with annotations, method call interception, a
// field, an interface implemented, and toString of the generated class.
public class Buddy {
    public static class Greeter { public String greet(String n) { return "hello " + n; } public int count() { return 1; } }
    public static class Interceptor {
        @RuntimeType public static Object intercept(@Origin Method m, @AllArguments Object[] args, @SuperCall Callable<?> zuper) throws Exception {
            return "[" + m.getName() + "] " + zuper.call();
        }
    }
    public static String shout(String n) { return n.toUpperCase() + "!"; }
    public static void main(String[] a) throws Exception {
        for (String strat : new String[] { "WRAPPER", "CHILD_FIRST", "INJECTION", "LOOKUP" }) {
            try {
                ClassLoadingStrategy<ClassLoader> st = switch (strat) {
                    case "WRAPPER" -> ClassLoadingStrategy.Default.WRAPPER;
                    case "CHILD_FIRST" -> ClassLoadingStrategy.Default.CHILD_FIRST;
                    case "INJECTION" -> ClassLoadingStrategy.Default.INJECTION;
                    default -> ClassLoadingStrategy.UsingLookup.of(java.lang.invoke.MethodHandles.privateLookupIn(Greeter.class, java.lang.invoke.MethodHandles.lookup()));
                };
                Class<? extends Greeter> c = new ByteBuddy().subclass(Greeter.class)
                    .method(ElementMatchers.named("count")).intercept(FixedValue.value(42))
                    .make().load(Buddy.class.getClassLoader(), st).getLoaded();
                Greeter g = c.getDeclaredConstructor().newInstance();
                System.out.println(strat + ": " + g.count() + " " + g.greet("x") + " " + (c.getSuperclass() == Greeter.class));
            } catch (Throwable e) { System.out.println(strat + ": " + e); }
        }
        Class<?> deleg = new ByteBuddy().subclass(Greeter.class).method(ElementMatchers.named("greet"))
            .intercept(MethodDelegation.to(Buddy.class)).make().load(Buddy.class.getClassLoader()).getLoaded();
        System.out.println("delegation: " + ((Greeter) deleg.getDeclaredConstructor().newInstance()).greet("bob"));
        Class<?> inter = new ByteBuddy().subclass(Greeter.class).method(ElementMatchers.named("greet"))
            .intercept(MethodDelegation.to(Interceptor.class)).make().load(Buddy.class.getClassLoader()).getLoaded();
        Greeter gi = (Greeter) inter.getDeclaredConstructor().newInstance();
        System.out.println("intercept: " + gi.greet("amy") + " / " + gi.count());
        Class<?> sup = new ByteBuddy().subclass(Object.class).implement(Supplier.class).name("gen.MySupplier")
            .defineField("v", String.class, Modifier.PUBLIC).method(ElementMatchers.named("get")).intercept(FieldAccessor.ofField("v"))
            .make().load(Buddy.class.getClassLoader()).getLoaded();
        Object o = sup.getDeclaredConstructor().newInstance();
        sup.getField("v").set(o, "field value");
        System.out.println("supplier: " + ((Supplier<?>) o).get() + " " + sup.getName() + " " + Supplier.class.isInstance(o));
        Class<?> ts = new ByteBuddy().subclass(Object.class).method(ElementMatchers.named("toString")).intercept(FixedValue.value("generated!"))
            .make().load(Buddy.class.getClassLoader()).getLoaded();
        System.out.println("toString: " + ts.getDeclaredConstructor().newInstance());
    }
}
