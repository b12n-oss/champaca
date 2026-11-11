import picocli.CommandLine;
import picocli.CommandLine.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;

// picocli: annotated options and parameters, subcommands, type conversion,
// help and usage text, errors, a mixin, an interface-defined command (a
// Proxy), and the exit codes, printed through StringWriters.
public class Pico {
    @Command(name = "tool", mixinStandardHelpOptions = true, version = "tool 1.2",
             description = "Does things.", subcommands = { Pico.Add.class, CommandLine.HelpCommand.class })
    static class Tool implements Callable<Integer> {
        @Option(names = { "-v", "--verbose" }, description = "Verbose.") boolean[] verbose = new boolean[0];
        @Option(names = "--count", defaultValue = "3", description = "How many (default: ${DEFAULT-VALUE}).") int count;
        @Option(names = "--unit", description = "One of: ${COMPLETION-CANDIDATES}") java.util.concurrent.TimeUnit unit = java.util.concurrent.TimeUnit.SECONDS;
        @Option(names = "-D", mapFallbackValue = "") Map<String, String> props = new LinkedHashMap<>();
        @Option(names = "--size", split = ",") List<Integer> sizes = new ArrayList<>();
        @Option(names = "--file") File file;
        @Option(names = "--ratio") double ratio = 0.5;
        @Parameters(paramLabel = "ITEM", description = "Items.") List<String> items = new ArrayList<>();
        @Spec Model.CommandSpec spec;
        public Integer call() {
            spec.commandLine().getOut().println("verbose=" + verbose.length + " count=" + count + " unit=" + unit + " props=" + props + " sizes=" + sizes
                + " file=" + file + " ratio=" + ratio + " items=" + items);
            return items.size();
        }
    }
    @Command(name = "add", description = "Adds numbers.")
    static class Add implements Callable<Integer> {
        @Mixin Shared shared;
        @Parameters(arity = "1..*") int[] nums;
        @ParentCommand Tool parent;
        public Integer call() { int s = Arrays.stream(nums).sum(); System.out.println("sum " + s + " quiet=" + shared.quiet + " parentCount=" + parent.count); return 0; }
    }
    static class Shared { @Option(names = "-q") boolean quiet; }
    @Command(name = "iface")
    interface Iface { @Option(names = "--name") String name(); @Option(names = "--n", defaultValue = "7") int n(); }

    static void run(String label, Object cmd, String... args) {
        StringWriter out = new StringWriter(), err = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).setUsageHelpWidth(80);
        int code = cl.execute(args);
        System.out.println("== " + label + " exit " + code);
        System.out.print(out);
        System.out.print(err.toString().replace("\r", ""));
    }
    public static void main(String[] a) {
        run("plain", new Tool(), "-vv", "--count=5", "--unit", "MINUTES", "-Dk=v", "-Dflag", "--size", "1,2,3", "--file", "x.txt", "--ratio", "1e-2", "a", "b");
        run("defaults", new Tool());
        run("help", new Tool(), "--help");
        run("version", new Tool(), "-V");
        run("sub", new Tool(), "--count", "9", "add", "-q", "1", "2", "39");
        run("sub help", new Tool(), "help", "add");
        run("bad number", new Tool(), "--count", "x");
        run("bad enum", new Tool(), "--unit", "WEEKS");
        run("unknown", new Tool(), "--nope");
        run("missing arity", new Tool(), "add");
        CommandLine ci = new CommandLine(Iface.class);
        ci.parseArgs("--name", "z");
        Iface i = ci.getCommand();
        System.out.println("iface " + i.name() + " " + i.n() + " proxy " + java.lang.reflect.Proxy.isProxyClass(i.getClass()));
        CommandLine.ParseResult pr = new CommandLine(new Tool()).parseArgs("-v", "--size=4,5", "q");
        System.out.println("parse " + pr.hasMatchedOption("-v") + " " + pr.matchedOptionValue("--size", List.of()) + " " + pr.matchedPositionals().size());
    }
}
