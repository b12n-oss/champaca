package champaca.dispatch.a;

// DispatchCheck makes GreetsSub.hi() private in its class file: a private
// method overrides nothing, so the call selects this default
public interface Greets {
    default String hi() { return "default-hi"; }
    static String call(Greets g) { return g.hi(); }
}
