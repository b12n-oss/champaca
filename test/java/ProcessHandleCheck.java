import java.time.*;
import java.util.*;

// ProcessHandle beyond starting processes: start times, CPU times, users
// and command lines (ProcessHandle.Info), children and descendants, parents,
// and handles to processes that have gone. Only what holds on any machine
// prints: where the sleep binary lives differs, and macOS reports another
// process's CPU time to no one, where Linux reports it to anyone.
public class ProcessHandleCheck {
  static void row(String name, Object v) { System.out.println(name + ": " + v); }
  static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

  public static void main(String[] a) throws Exception {
    ProcessHandle self = ProcessHandle.current();
    ProcessHandle.Info si = self.info();
    row("self info", si.startInstant().isPresent() + " " + si.totalCpuDuration().isPresent() + " "
        + si.user().map(u -> u.equals(System.getProperty("user.name"))).orElse(false) + " " + si.command().isPresent());
    row("self started before now", si.startInstant().get().isBefore(Instant.now()));
    row("self cpu positive", si.totalCpuDuration().get().toNanos() > 0);

    Instant before = Instant.now().minusSeconds(2);
    Process p = new ProcessBuilder("sleep", "30").start();
    Instant after = Instant.now().plusSeconds(2);
    ProcessHandle h = p.toHandle();
    ProcessHandle.Info i = h.info();
    row("child command", i.command().map(c -> c.endsWith("/sleep")).orElse(false));
    row("child arguments", i.arguments().map(Arrays::toString).orElse("none"));
    row("child command line", i.commandLine().map(l -> l.equals(i.command().get() + " 30")).orElse(false));
    row("child user", i.user().map(u -> u.equals(System.getProperty("user.name"))).orElse(false));
    row("child start", i.startInstant().map(s -> !s.isBefore(before) && !s.isAfter(after)).orElse(false));
    row("child cpu as this system", i.totalCpuDuration().isPresent() != MAC);
    row("child of self", self.children().anyMatch(c -> c.pid() == h.pid()) + " "
        + self.descendants().anyMatch(c -> c.pid() == h.pid()) + " " + h.parent().map(ProcessHandle::pid).equals(Optional.of(self.pid())));
    row("all processes", ProcessHandle.allProcesses().anyMatch(x -> x.pid() == h.pid()) + " "
        + ProcessHandle.allProcesses().anyMatch(x -> x.pid() == self.pid()));
    row("of", ProcessHandle.of(h.pid()).map(x -> x.equals(h) && x.isAlive()).orElse(false));
    row("same start time", ProcessHandle.of(h.pid()).get().info().startInstant().equals(i.startInstant()));
    row("compare", h.compareTo(ProcessHandle.of(h.pid()).get()) + " " + (h.hashCode() == ProcessHandle.of(h.pid()).get().hashCode()));

    // a grandchild: sh starts sleep, which is the shell's child
    Process sh = new ProcessBuilder("sh", "-c", "sleep 30 & wait").start();
    ProcessHandle shh = sh.toHandle();
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (shh.children().count() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
    row("grandchild", shh.children().count() + " " + self.descendants().anyMatch(d -> d.parent().map(pp -> pp.pid() == shh.pid()).orElse(false))
        + " " + self.children().noneMatch(c -> shh.children().anyMatch(g -> g.pid() == c.pid())));
    shh.descendants().forEach(ProcessHandle::destroyForcibly);
    sh.destroyForcibly(); sh.waitFor();

    p.destroy();
    row("exit", p.waitFor() + " " + p.exitValue());
    row("gone", h.isAlive() + " " + ProcessHandle.of(h.pid()).isPresent() + " " + h.info().startInstant().isPresent()
        + " " + h.destroy() + " " + h.parent().isPresent());
    row("no such process", ProcessHandle.of(Integer.MAX_VALUE).isPresent());
  }
}
