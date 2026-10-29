package d;

// v2: a field before n, so A's compiled code for b.n reads another slot
// unless it is compiled again; other values too
public class B {
    public long pad = 7;
    public int n = 2;
    public int value() { return 20; }
    public static String tag() { return "v2"; }
}
