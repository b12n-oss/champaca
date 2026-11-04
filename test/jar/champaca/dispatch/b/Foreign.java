package champaca.dispatch.b;

// another package: neither method overrides Base's
public class Foreign extends champaca.dispatch.a.Base {
    public String m() { return "foreign"; }
    public String h() { return "foreign-h"; }
    // a private g() beside Base's package-private one: javac references it
    // as invokeVirtual (kind 5), and the vtable's g() entry is Base's
    private String g() { return "foreign-g"; }
    public String viaPrivateReference() {
        java.util.function.Supplier<String> s = this::g;
        return s.get();
    }
}
