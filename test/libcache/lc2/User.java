package lc2;

import java.util.function.IntUnaryOperator;

import lc.Sub;

// In a jar of its own. It reads and writes Sub's field b, whose place in the
// object Base decides, though nothing here names Base.
public class User {
    public static String run() {
        Sub s = new Sub(1, 2);
        // a lambda in a cached method: its class has to be there when the method is
        IntUnaryOperator plus = x -> x + 40;
        s.b = plus.applyAsInt(s.b);
        return s.describe() + " direct b=" + s.b;
    }

    // a jar class's frames keep their line numbers, cached or not
    public static String frame() {
        StackTraceElement e = new Throwable().getStackTrace()[0];
        return e.getFileName() + ":" + e.getLineNumber();
    }
}
