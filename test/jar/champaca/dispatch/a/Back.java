package champaca.dispatch.a;

// Back is in Base's package, below Mid, whose public m() doesn't override
// Base's: Back.m() overrides both
public class Back extends champaca.dispatch.b.Mid {
    public String m() { return "back"; }
}
