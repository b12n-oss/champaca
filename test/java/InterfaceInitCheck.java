// When interfaces are initialized (JVMS 5.5): a class's initialization
// first initializes its superclass, then each superinterface, direct or
// not, that declares a non-abstract, non-static method, in a recursive
// walk of the interfaces arrays that visits an interface's superinterfaces
// before it. An interface with only abstract or static methods waits for a
// use of its own. A default method that reads its interface's static field
// sees it set (Spring Boot's PropertyMapper does).
public class InterfaceInitCheck {
    static String log(String s) { System.out.println("init " + s); return s; }

    interface Plain { String P = log("Plain"); void run(); }
    interface Statics { String S = log("Statics"); static String hello() { return "hello"; } }
    interface Base { String B = log("Base"); default String base() { return B; } }
    interface Mid extends Base { String M = log("Mid"); default String mid() { return M; } }
    interface Other { String O = log("Other"); default String other() { return O; } }
    interface Top extends Mid, Plain { String T = log("Top"); }
    interface Getter { Object DEFAULT = log("Getter"); default Object get() { return DEFAULT; } }

    static class Super implements Other { static { log("Super"); } public void run() {} }
    static class Impl extends Super implements Top, Statics { static { log("Impl"); } }
    static class UsesGetter implements Getter { static { log("UsesGetter"); } }
    static class Sub extends Impl { static { log("Sub"); } }

    public static void main(String[] args) {
        System.out.println("-- new Impl");
        Impl i = new Impl();
        System.out.println("base " + i.base() + " mid " + i.mid() + " other " + i.other());
        System.out.println("-- Statics.hello");
        System.out.println(Statics.hello());
        System.out.println("-- Plain.P");
        System.out.println(Plain.P);
        System.out.println("-- new Sub (Impl already initialized)");
        new Sub();
        System.out.println("-- a default method reading its interface's static");
        System.out.println("get " + new UsesGetter().get());
        System.out.println("-- Top.T");
        System.out.println(Top.T);
    }
}
