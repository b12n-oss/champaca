import java.lang.reflect.*;

// A class file that exists but can't be parsed is a ClassFormatError from the
// instruction that first needs its class, and from every later one, while
// code that doesn't reach it runs. CfCaller, CfTruncated and CfBadMagic come
// from test/gen/ClassFormatGen.java.
public class ClassFormatCheck {
    static String call(Class<?> c, String name) {
        try { return String.valueOf(c.getMethod(name).invoke(null)); }
        catch (InvocationTargetException e) { return e.getCause().getClass().getName() + ": " + e.getCause().getMessage(); }
        catch (Throwable t) { return "unexpected " + t; }
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("CfCaller");
        System.out.println("fine: " + call(c, "fine"));
        for (String m : new String[] {"newIt", "newIt", "ldcIt", "callIt", "getIt", "castIt", "isIt", "newBadMagic", "newBadMagic", "newSub"})
            System.out.println(m + ": " + call(c, m));
        try { Class.forName("CfTruncated"); System.out.println("forName: loaded"); }
        catch (Throwable t) { System.out.println("forName: " + t); }
        System.out.println("fine after: " + call(c, "fine"));
    }
}
