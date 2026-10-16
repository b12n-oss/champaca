package champaca.lib;

// A service interface, found through META-INF/services by ServiceLoader.
public interface Greeter {
  String greet(String name);
}
