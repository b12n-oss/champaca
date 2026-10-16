// Which default method a class gets: the most specific superinterface's,
// unless a class in the superclass chain declares the method.
public class DefaultCheck {
    interface A { default String who() { return "A"; } }
    interface B extends A { default String who() { return "B"; } }
    interface Other { default String who() { return "Other"; } }

    // listing the less specific interface first changes nothing
    static class C implements A, B { }
    static final class FinalC implements A, B { }
    // a superclass got A's default; the subclass also implements B
    static class Base implements A { }
    static class Sub extends Base implements B { }
    static final class FinalSub extends Base implements B { }
    // a class declaration beats any default
    static class Decl implements B { public String who() { return "Decl"; } }
    static class DeclSub extends Decl implements B { }
    // an interface extending B, deeper still
    interface D extends B { }
    interface E extends D { default String who() { return "E"; } }
    static class Deep implements B, D, E { }
    // A.super / B.super pick a specific one explicitly
    static class Pick implements A, Other {
        public String who() { return A.super.who() + "+" + Other.super.who(); }
    }

    // a default calling the interface's private and static methods
    interface Priv {
        private String secret() { return "private"; }
        static String shared() { return "static"; }
        default String reveal() { return secret() + "+" + shared(); }
    }
    static class UsesPriv implements Priv { }
    static class SubPriv extends UsesPriv { String secret() { return "class's own"; } }

    // a lambda for an interface with a default
    interface Fn { String apply(); default String twice() { return apply() + apply(); } }

    // a generic interface whose override is reached through a bridge
    interface Gen<T> { default String take(T t) { return "Gen"; } }
    interface StrGen extends Gen<String> { default String take(String s) { return "StrGen " + s; } }
    static class UsesGen implements StrGen { }

    static String call(A a) { return a.who(); }

    public static void main(String[] args) {
        System.out.println(new C().who() + " " + call(new C()) + " " + new FinalC().who() + " " + call(new FinalC()));
        System.out.println(new Base().who() + " " + new Sub().who() + " " + call(new Sub()) + " " + new FinalSub().who() + " " + call(new FinalSub()));
        System.out.println(new Decl().who() + " " + new DeclSub().who() + " " + call(new DeclSub()));
        System.out.println(new Deep().who() + " " + call(new Deep()));
        System.out.println(new Pick().who());
        System.out.println(new UsesPriv().reveal() + " " + new SubPriv().reveal() + " " + new SubPriv().secret());
        Fn f = () -> "x";
        System.out.println(f.twice());
        Gen<String> g = new UsesGen();
        System.out.println(g.take("s") + " " + new UsesGen().take("t"));
    }
}
