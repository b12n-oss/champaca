import java.util.function.*;

// A method reference to a static method or a constructor initializes its
// class when the lambda is first called, not when it is made (JVMS 5.5: the
// method handle's invocation does it), and every call after that finds the
// class ready. Each class here prints from its static initializer, so the
// order of the lines shows when that ran.
public class LambdaInitCheck {
    static class Lazy {
        static { System.out.println("Lazy initialized"); }
        static int seven() { return 7; }
    }
    static class Made {
        static { System.out.println("Made initialized"); }
        final String tag;
        Made() { tag = "made"; }
        Made(String t) { tag = t; }
    }
    static class Fails {
        static { if (true) throw new IllegalStateException("no"); }
        static int never() { return 0; }
    }
    static class Twice {
        static int inits;
        static { inits++; System.out.println("Twice initialized"); }
        static int count() { return inits; }
    }

    public static void main(String[] args) {
        IntSupplier s = Lazy::seven;
        System.out.println("static reference made");
        System.out.println("call: " + s.getAsInt());
        System.out.println("again: " + s.getAsInt());

        Supplier<Made> m = Made::new;
        Function<String, Made> mf = Made::new;
        System.out.println("constructor references made");
        System.out.println("new: " + m.get().tag + " " + mf.apply("given").tag);

        IntSupplier f = Fails::never;
        for (int i = 0; i < 2; i++) {
            try { f.getAsInt(); System.out.println("no exception"); }
            catch (Throwable t) { System.out.println("fails: " + t.getClass().getName() + (t.getCause() != null ? " caused by " + t.getCause() : "")); }
        }

        IntSupplier a = Twice::count, b = Twice::count;
        System.out.println("twice: " + a.getAsInt() + " " + b.getAsInt() + " " + Twice.count());
    }
}
