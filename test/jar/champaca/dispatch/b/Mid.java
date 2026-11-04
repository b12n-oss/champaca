package champaca.dispatch.b;

// public, in another package: doesn't override Base.m()
public class Mid extends champaca.dispatch.a.Base {
    public String m() { return "mid"; }
}
