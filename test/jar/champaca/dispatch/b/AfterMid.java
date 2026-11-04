package champaca.dispatch.b;

// in another package, but overriding Back.m(), which overrides Base.m():
// transitively it overrides Base.m() (JVMS 5.4.5)
public class AfterMid extends champaca.dispatch.a.Back {
    public String m() { return "after-mid"; }
}
