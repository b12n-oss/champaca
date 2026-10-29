package d;

public class A extends S {
    public int k = 1;
    public static String run() {
        B b = new B();
        A a = new A();
        F f = x -> x + 1;
        return b.value() + " " + B.tag() + " " + b.n + " " + a.twice(b.n) + " " + f.f(b.n) + " " + a.sv() + " " + a.k;
    }
    int twice(int x) { return 2 * x; }
    // called only when DefMain is told to: after a change to B, a run that
    // calls run alone must still drop other's cached code, compiled against
    // the old B, for the run after it to compile other anew
    public static String other() { return "other " + new B().n; }
}
