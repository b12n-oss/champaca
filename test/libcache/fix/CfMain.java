// test/libcache/fix: calls CfCaller.newIt, whose `new CfTruncated` is a
// ClassFormatError while CfTruncated.class is cut short (test/gen/
// ClassFormatGen.java). jolt test runs it twice so, then again after copying the
// whole class file in: a method holding that throw is never cached, so the
// third run makes the object.
public class CfMain {
    public static void main(String[] args) throws Exception {
        try { System.out.println(Class.forName("CfCaller").getMethod("newIt").invoke(null)); }
        catch (java.lang.reflect.InvocationTargetException e) { System.out.println(e.getCause()); }
    }
}
