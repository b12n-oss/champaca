package champaca.lib;

/** A second class for PortSample to call into (examples/port.clj). */
public class PortOther {
    /** Gives the class a static initializer. */
    static final int[] TABLE = {1, 2, 3};

    public static int clamp(int x, int lo, int hi) { return x < lo ? lo : x > hi ? hi : x; }
    public static int clamp(int x, int hi) { return clamp(x, 0, hi); }
    public static boolean vowel(char c) { return "aeiou".indexOf(c) >= 0; }
    /** Reads a static field: left, and so is what calls it. */
    public static int tableAt(int i) { return TABLE[i]; }
}
