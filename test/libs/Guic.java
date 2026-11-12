import com.google.inject.*;
import com.google.inject.name.*;
import jakarta.inject.Inject;
import java.util.*;
public class Guic {
  public interface Greeter { String greet(String n); }
  public static class Polite implements Greeter { public String greet(String n) { return "Good day, " + n; } }
  public static class Counter { int n; }
  public static class Service {
    final Greeter g; final String prefix; final Provider<Counter> counters; @Inject Set<String> plugins;
    @Inject public Service(Greeter g, @Named("prefix") String prefix, Provider<Counter> counters) { this.g = g; this.prefix = prefix; this.counters = counters; }
    String run() { return prefix + g.greet("Ada") + " " + (counters.get() == counters.get()) + " " + new TreeSet<>(plugins); }
  }
  public static void main(String[] a) {
    Injector inj = Guice.createInjector(new AbstractModule() {
      @Override protected void configure() {
        bind(Greeter.class).to(Polite.class);
        bindConstant().annotatedWith(Names.named("prefix")).to(">> ");
        bind(Counter.class).in(Singleton.class);
        var mb = com.google.inject.multibindings.Multibinder.newSetBinder(binder(), String.class);
        mb.addBinding().toInstance("b"); mb.addBinding().toInstance("a");
      }
      @Provides @Named("answer") Integer answer() { return 42; }
    });
    System.out.println(inj.getInstance(Service.class).run());
    System.out.println(inj.getInstance(Key.get(Integer.class, Names.named("answer"))));
    System.out.println(inj.getInstance(Counter.class) == inj.getInstance(Counter.class));
    try { inj.getInstance(Runnable.class); } catch (ConfigurationException e) { System.out.println("missing: " + e.getErrorMessages().size()); }
    System.out.println(inj.getBindings().keySet().stream().map(k -> k.getTypeLiteral().getRawType().getSimpleName()).sorted().distinct().toList());
  }
}
