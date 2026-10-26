package lc2;

import lc.Sub;

// In a jar of its own. It reads and writes Sub's field b, whose place in the
// object Base decides, though nothing here names Base.
public class User {
    public static String run() {
        Sub s = new Sub(1, 2);
        s.b += 40;
        return s.describe() + " direct b=" + s.b;
    }
}
