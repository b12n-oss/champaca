// Loaded through DirLoader, in the unnamed package with DefMain and Gen. It
// extends Gen, so its field k sits after Gen's: a cached registration of A2
// would lay k out for the Gen of an earlier run
public class A2 extends Gen {
    public int k = 5;
    public static String run() { A2 a = new A2(); return "gen " + a.g + " " + a.k; }
}
