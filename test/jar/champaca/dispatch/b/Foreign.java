package champaca.dispatch.b;

// another package: neither method overrides Base's
public class Foreign extends champaca.dispatch.a.Base {
    public String m() { return "foreign"; }
    public String h() { return "foreign-h"; }
}
