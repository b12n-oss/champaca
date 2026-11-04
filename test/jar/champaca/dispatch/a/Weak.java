package champaca.dispatch.a;

// DispatchCheck patches WeakSub.w() private in its class file (javac refuses
// that): a private method overrides nothing, so a call of Weak.w() on a
// WeakSub still selects Weak's
public class Weak {
    public String w() { return "weak"; }
    public static String call(Weak x) { return x.w(); }
}
