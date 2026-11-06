// JDK classes named in the bytecode, not in strings: under --limit-modules
// (or for a module the boot layer leaves out) each instruction that resolves
// one throws NoClassDefFoundError when it runs, and the same error again on
// a second try; a descriptor naming one and instanceof or checkcast of
// null resolve nothing. (A catch clause's type is left out: HotSpot's verifier
// loads it when it links the class, and champaca doesn't verify.)
public class Ref {
    interface Probe { Object run() throws Throwable; }

    static void row(String what, Probe p) {
        try {
            System.out.println(what + ": " + p.run());
        } catch (Throwable e) {
            Throwable c = e.getCause();
            System.out.println(what + ": " + e.getClass().getName() + ": " + e.getMessage()
                               + (c == null ? "" : "; cause " + c.getClass().getName() + ": " + c.getMessage()));
        }
    }

    static Object ldc() { return java.sql.Connection.class.getName(); }
    static Object invokestatic() { return java.sql.DriverManager.getLoginTimeout(); }
    static Object newTimestamp() { return new java.sql.Timestamp(0L).getTime(); }
    static Object getstatic() { return java.util.logging.Level.OFF.getName(); }
    static Object httpClient() { return java.net.http.HttpClient.Version.HTTP_2.name(); }
    static Object instanceOf(Object o) { return o instanceof java.sql.Connection; }
    static Object checkCast(Object o) { return (java.sql.Connection) o; }
    static Object descriptor(java.sql.Connection c) { return "called with " + c; }
    static Object array() { return new java.sql.Connection[2].length; }
    static Object invokevirtual(java.sql.Timestamp t) { return t.getNanos(); }
    static Object invokeinterface(java.sql.Connection c) throws Exception { return c.isClosed(); }
    static Object getfield(java.awt.Point p) { return p.x; }
    public static void main(String[] a) {
        row("ldc", Ref::ldc);
        row("ldc again", Ref::ldc);
        row("invokestatic", Ref::invokestatic);
        row("new", Ref::newTimestamp);
        row("getstatic java.logging", Ref::getstatic);
        row("java.net.http", Ref::httpClient);
        row("instanceof null", () -> instanceOf(null));
        row("instanceof string", () -> instanceOf("x"));
        row("checkcast null", () -> checkCast(null));
        row("descriptor only", () -> descriptor(null));
        row("anewarray", Ref::array);
        row("invokevirtual on null", () -> invokevirtual(null));
        row("invokeinterface on null", () -> invokeinterface(null));
        row("getfield on null", () -> getfield(null));
    }
}
