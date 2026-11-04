package champaca.dispatch.a;

// Defined again by a loader of DispatchCheck's own: the same package name in
// another run-time package, so this m() doesn't override Base.m()
public class Isolated extends Base {
    String m() { return "isolated"; }
}
