package lc;

// Compiled once, against v1's Base: its bytes are the same in both jars
public class Sub extends Base {
    public int b;

    public Sub(int a, int b) { super(a); this.b = b; }

    public String describe() { return kind() + " a=" + a + " b=" + b; }
}
