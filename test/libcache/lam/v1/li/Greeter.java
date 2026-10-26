package li;

public interface Greeter {
    String name();

    default String greet() { return "hi " + name(); }
}
