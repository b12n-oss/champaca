import java.util.*;
import java.util.function.Supplier;

// ldc of a String and of a Class: each site keeps what it resolved, and what
// it keeps is the JVM's object, the same every time and from every site. Each
// row runs three times, so the first resolution and the kept one both answer.
public class LdcCheck {
    static String one() { return "the same literal"; }
    static String two() { return "the same literal"; }
    static String nonBmp() { return "a 😀 face"; }
    static Class<?> listClass() { return ArrayList.class; }
    static Class<?> arrayClass() { return Integer[][].class; }
    static Class<?> ownClass() { return LdcCheck.class; }

    static void row(String name, Object v) { System.out.println(name + ": " + v); }

    public static void main(String[] args) throws Exception {
        Supplier<String> fromLambda = () -> "the same literal";
        for (int pass = 0; pass < 3; pass++) {
            String p = " (pass " + pass + ")";
            row("two methods, one String" + p, one() == two());
            row("the interned String" + p, one() == new String("the same literal").intern());
            row("from a lambda" + p, one() == fromLambda.get());
            row("a constant expression" + p, one() == "the same " + "literal");
            row("outside the BMP" + p, nonBmp() == ("a 😀" + " face").intern() && nonBmp().length() == 9);
            row("ArrayList.class" + p, listClass() == Class.forName("java.util.ArrayList"));
            row("Integer[][].class" + p, (arrayClass() == Class.forName("[[Ljava.lang.Integer;")) + " " + arrayClass().getName());
            row("its own class" + p, ownClass() == new LdcCheck().getClass());
        }
    }
}
