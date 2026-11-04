package champaca.dispatch.b;

// overrides Bridge.m(), which is public and overrides Base.m(): transitively
// it overrides Base.m()
public class AfterBridge extends champaca.dispatch.a.Base.Bridge {
    public String m() { return "after-bridge"; }
}
