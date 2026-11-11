import java.lang.reflect.*;
import java.util.*;

// Names holding '|' or '\' (legal in the JVM, emitted by Scala and Kotlin,
// never by javac), through test/gen/OddNameGen.java's class Odd|Na\me:
// fields, a static, an instance and a throwing method run and reflect under
// their own names, and a stack trace names the throwing method.
public class OddNameCheck {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("Odd|Na\\me");
        System.out.println("class: " + c.getName());
        System.out.println("run: " + c.getMethod("run").invoke(null));
        List<String> methods = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) methods.add(m.getName());
        Collections.sort(methods);
        System.out.println("methods: " + methods);
        List<String> fields = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) fields.add(f.getName());
        Collections.sort(fields);
        System.out.println("fields: " + fields);
        Field sf = c.getDeclaredField("s|f");
        sf.setAccessible(true);
        System.out.println("s|f read: " + sf.get(null));
        Method twice = c.getDeclaredMethod("twice|it", int.class);
        twice.setAccessible(true);
        System.out.println("twice|it(5): " + twice.invoke(null, 5));
        try {
            c.getMethod("boom|\\it").invoke(null);
        } catch (InvocationTargetException e) {
            StackTraceElement top = e.getCause().getStackTrace()[0];
            System.out.println("thrown: " + e.getCause() + " at " + top.getClassName() + "." + top.getMethodName());
        }
    }
}
