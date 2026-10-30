import java.lang.reflect.*;

// A call whose instruction doesn't match the kind of method it resolves to
// throws IncompatibleClassChangeError when it runs, with HotSpot's text, and
// only then. IcCaller comes from test/gen/IcceGen.java, since javac won't
// compile such calls.
public class IcceCheck {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("IcCaller");
        for (String name : new String[] {"virtualOfStatic", "virtualOfStaticNull", "staticOfInstance", "interfaceOfStatic", "specialOfStatic"}) {
            Method m = c.getMethod(name, boolean.class);
            System.out.println(name + " not run: " + m.invoke(null, false));
            for (int i = 0; i < 2; i++) {
                try { System.out.println(name + ": " + m.invoke(null, true)); }
                catch (InvocationTargetException e) { System.out.println(name + ": " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage()); }
            }
        }
    }
}
