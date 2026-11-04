package champaca.dispatch.a;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;

// DispatchCheck's fixtures (JVMS 5.4.5): m() is package-private, so only a
// class of this run-time package overrides it, or one overriding a method
// that overrides it. The static helpers call m() from inside the package,
// each a different way.
public class Base {
    String m() { return "base"; }
    private String h() { return "base-private"; }

    public static String call(Base b) { return b.m(); }
    public static String callPrivate(Base b) { return b.h(); }
    public static String viaReference(Base b) {
        Function<Base, String> f = Base::m;
        return f.apply(b);
    }
    public static String viaHandle(Base b) throws Throwable {
        return (String) MethodHandles.lookup()
            .findVirtual(Base.class, "m", MethodType.methodType(String.class)).invoke(b);
    }
    public static String viaPrivateHandle(Base b) throws Throwable {
        return (String) MethodHandles.lookup()
            .findVirtual(Base.class, "h", MethodType.methodType(String.class)).invoke(b);
    }

    // same package: overrides
    public static class Same extends Base {
        String m() { return "same"; }
    }
    // same package and public: overrides, and anything below overrides it
    public static class Bridge extends Base {
        public String m() { return "bridge"; }
    }
    // same package, with a public h(): Base's private h() isn't overridden
    public static class SameH extends Base {
        public String h() { return "same-h"; }
    }
}
