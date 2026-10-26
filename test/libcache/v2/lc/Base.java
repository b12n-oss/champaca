package lc;

public class Base {
    // a field ahead of the others, so every field of a subclass moves
    protected long pad = 99;
    protected int a;

    public Base(int a) { this.a = a; }

    public String kind() { return "v2"; }
}
