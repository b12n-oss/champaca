import java.lang.reflect.*;

// A class file that exists but can't be parsed is a ClassFormatError from the
// instruction that first needs its class, and from every later one, while
// code that doesn't reach it runs. CfCaller, CfTruncated and CfBadMagic come
// from test/gen/ClassFormatGen.java.
public class ClassFormatCheck extends ClassLoader {
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
        // the same from defineClass: a wrong magic number names the class
        // given (slashed, as HotSpot's internal name) or <Unknown>, and every
        // prefix of a class file, wherever it ends, is a truncated file
        byte[] whole = ClassFormatCheck.class.getResourceAsStream("ClassFormatCheck.class").readAllBytes();
        byte[] bad = whole.clone();
        bad[0] = 0x12; bad[1] = 0x34; bad[2] = 0x56; bad[3] = 0x78;
        ClassFormatCheck l = new ClassFormatCheck();
        for (String n : new String[] {"p.Q", null}) {
            try { l.defineClass(n, bad, 0, bad.length); System.out.println("defined"); }
            catch (Throwable t) { System.out.println("defineClass " + n + ": " + t); }
        }
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (int len = 0; len < whole.length; len++) {
            try { l.defineClass(null, whole, 0, len); seen.add("defined at " + len); }
            catch (Throwable t) { seen.add(t.toString()); }
        }
        System.out.println("every prefix: " + seen);
    }
}
