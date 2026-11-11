import java.lang.reflect.*;

// jsr, jsr_w and ret, in a version 49 class written by test/gen/JsrGen.java
// (javac can't emit them). Each row calls one of Jsr's methods, a shape
// javac 1.4 compiled finally blocks into, and prints what it returns or
// throws.
public class JsrCheck {
    static Class<?> jsr;

    static String call(String name, Class<?> type, Object arg) {
        try {
            return String.valueOf(jsr.getMethod(name, type).invoke(null, arg));
        } catch (InvocationTargetException e) {
            return "threw " + e.getCause();
        } catch (ReflectiveOperationException e) {
            return "reflection " + e;
        }
    }

    static int count() throws Exception { return jsr.getField("count").getInt(null); }

    public static void main(String[] args) throws Exception {
        jsr = Class.forName("Jsr");
        System.out.println("fin 4: " + call("fin", int.class, 4) + ", count " + count());
        System.out.println("fin 0: " + call("fin", int.class, 0) + ", count " + count());
        System.out.println("fin -5: " + call("fin", int.class, -5) + ", count " + count());
        System.out.println("two 1: " + call("two", int.class, 1));
        System.out.println("two -10: " + call("two", int.class, -10));
        System.out.println("nested 0: " + call("nested", int.class, 0));
        System.out.println("nested 1000: " + call("nested", int.class, 1000));
        System.out.println("wide 5: " + call("wide", int.class, 5));
        System.out.println("loop []: " + call("loop", int[].class, new int[0]));
        System.out.println("loop [1..10]: " + call("loop", int[].class, new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}));
        int[] big = new int[1000];
        for (int i = 0; i < big.length; i++) big[i] = i * 3;
        System.out.println("loop 1000: " + call("loop", int[].class, big));
        System.out.println("loop null: " + call("loop", int[].class, null));
        System.out.println("longs 0: " + call("longs", long.class, 0L));
        System.out.println("longs -1: " + call("longs", long.class, -1L));
        System.out.println("longs max: " + call("longs", long.class, Long.MAX_VALUE));
        System.out.println("longs 0xffffffff: " + call("longs", long.class, 0xffffffffL));
        System.out.println("noReturn 7: " + call("noReturn", int.class, 7));
        System.out.println("stack 37: " + call("stack", int.class, 37));
        System.out.println("throwOut 3: " + call("throwOut", int.class, 3));
        System.out.println("throwOut 0: " + call("throwOut", int.class, 0));
        System.out.println("throwIn 3: " + call("throwIn", int.class, 3));
        System.out.println("throwIn 0: " + call("throwIn", int.class, 0));
        System.out.println("stackNested 33: " + call("stackNested", int.class, 33));
        System.out.println("staleLocal 1: " + call("staleLocal", int.class, 1));
        System.out.println("wideNpe abc: " + call("wideNpe", String.class, "abc"));
        System.out.println("wideNpe null: " + call("wideNpe", String.class, null));
        System.out.println("jsrNpe null: " + call("jsrNpe", String.class, null));
        System.out.println("npeBeforeJsr null: " + call("npeBeforeJsr", String.class, null));
        System.out.println("reuse 1: " + call("reuse", int.class, 1));
        // the same methods again, once they've been compiled
        int sum = 0;
        for (int i = 0; i < 200; i++) sum += Integer.parseInt(call("two", int.class, i)) + Integer.parseInt(call("nested", int.class, i));
        System.out.println("repeated: " + sum);
    }
}
