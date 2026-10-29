package d;

public class A {
    public static String run() {
        B b = new B();
        F f = x -> x + 1;
        return b.value() + " " + B.tag() + " " + b.n + " " + new A().twice(b.n) + " " + f.f(b.n);
    }
    int twice(int x) { return 2 * x; }
}
