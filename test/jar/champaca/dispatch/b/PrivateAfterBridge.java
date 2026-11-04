package champaca.dispatch.b;

// DispatchCheck makes this m() private in its class file: below Bridge's
// public m() it would override Base.m(), but a private method overrides
// nothing, so a call of Base.m() selects Bridge's
public class PrivateAfterBridge extends champaca.dispatch.a.Base.Bridge {
    public String m() { return "private-after-bridge"; }
}
