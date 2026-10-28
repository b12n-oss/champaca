import java.lang.reflect.*;

// A call to a method its class doesn't have throws NoSuchMethodError when it
// runs, and only then (JVMS 5.4.3.3), so the method holding it runs fine on
// other paths. NmCaller comes from test/gen/NoMethodGen.java, since javac
// won't compile such a call.
public class NoMethodCheck {
    static String call(Method m, Object recv, boolean go) {
        try { return String.valueOf(m.invoke(recv, go)); }
        catch (InvocationTargetException e) { return e.getCause().getClass().getName() + ": " + e.getCause().getMessage(); }
        catch (Throwable t) { return "unexpected " + t; }
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("NmCaller");
        Object o = c.getConstructor().newInstance();
        Method gone = c.getMethod("callGone", boolean.class);
        Method sup = c.getMethod("callSuper", boolean.class);
        System.out.println("static, not called: " + call(gone, null, false));
        System.out.println("static, called: " + call(gone, null, true));
        System.out.println("static, called again: " + call(gone, null, true));
        System.out.println("super, not called: " + call(sup, o, false));
        System.out.println("super, called: " + call(sup, o, true));
    }
}
