package li;

// v1 plus a default method, which a lambda's class takes into its vtable
public interface Greeter {
    String name();

    default String greet() { return "hello " + name(); }

    default String shout() { return name().toUpperCase(); }
}
