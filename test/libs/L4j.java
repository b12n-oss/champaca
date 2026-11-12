import org.apache.logging.log4j.*;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.*;
import org.apache.logging.log4j.core.config.builder.api.*;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import java.io.*;
public class L4j {
  public static void main(String[] a) throws Exception {
    ConfigurationBuilder<BuiltConfiguration> b = ConfigurationBuilderFactory.newConfigurationBuilder();
    b.setStatusLevel(Level.ERROR);
    b.add(b.newAppender("out", "Console").addAttribute("target", "SYSTEM_OUT")
        .add(b.newLayout("PatternLayout").addAttribute("pattern", "%-5level %logger{1} [%t] %marker %X{user} - %msg%n%throwable{short.message}")));
    b.add(b.newLogger("probe.quiet", Level.WARN).add(b.newAppenderRef("out")).addAttribute("additivity", false));
    b.add(b.newRootLogger(Level.DEBUG).add(b.newAppenderRef("out")));
    LoggerContext ctx = Configurator.initialize(b.build());
    Logger log = LogManager.getLogger("probe.Main");
    log.info("hello {} and {}", "world", 42);
    log.debug("debug line");
    ThreadContext.put("user", "ada");
    log.warn(MarkerManager.getMarker("AUDIT"), "with a marker");
    ThreadContext.clearAll();
    LogManager.getLogger("probe.quiet").info("not shown");
    LogManager.getLogger("probe.quiet").error("shown");
    log.error("failed", new IllegalStateException("bad state"));
    Thread t = new Thread(() -> log.info("from {}", Thread.currentThread().getName()), "worker-1"); t.start(); t.join();
    System.out.println(log.isTraceEnabled() + " " + log.isDebugEnabled() + " " + ctx.getConfiguration().getAppenders().keySet());
    Configurator.shutdown(ctx);
  }
}
