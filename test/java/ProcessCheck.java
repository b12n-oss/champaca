import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

// Child processes: ProcessBuilder and Runtime.exec with pipes, files,
// discarded and inherited streams, the environment and working directory,
// exit codes (signals too), waitFor with a timeout, destroy and
// destroyForcibly, onExit, pipelines, and launch failures with the JDK's
// texts. Only /bin/sh and the POSIX tools every system has are run, and
// nothing printed depends on the machine.
public class ProcessCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable<?> c) {
        try { Object r = c.call(); return "no exception (" + r + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    static String out(Process p) throws IOException { return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip(); }
    static String err(Process p) throws IOException { return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip(); }
    static Process sh(String script) throws IOException { return new ProcessBuilder("/bin/sh", "-c", script).start(); }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("pcheck");
        try {
            Process p = new ProcessBuilder("echo", "hello", "world").start();
            row("echo", out(p) + " | " + p.waitFor() + " " + p.isAlive() + " " + p.exitValue() + " " + (p.pid() > 0)
                + " " + (p.toHandle().pid() == p.pid()) + " " + p.info().getClass().getSimpleName().isEmpty());
            for (String code : new String[]{"0", "3", "255"}) {
                Process q = sh("exit " + code);
                row("exit " + code, q.waitFor());
            }
            Process k = sh("kill -9 $$");
            row("killed by a signal", k.waitFor());
            // an ignored signal stays ignored across exec; the JVM's child gets SIGPIPE's default
            Process pipe = sh("kill -PIPE $$; echo survived");
            row("SIGPIPE default", out(pipe) + " " + pipe.waitFor());

            Process cat = new ProcessBuilder("cat").start();
            try (Writer w = new OutputStreamWriter(cat.getOutputStream(), StandardCharsets.UTF_8)) { w.write("line one\nline two\n"); }
            row("stdin to cat", out(cat).replace("\n", "|") + " " + cat.waitFor());

            Process both = sh("echo to-out; echo to-err 1>&2; exit 2");
            row("separate streams", out(both) + " / " + err(both) + " " + both.waitFor());
            Process merged = new ProcessBuilder("/bin/sh", "-c", "echo first; echo second 1>&2").redirectErrorStream(true).start();
            row("merged streams", out(merged).replace("\n", "|") + " [" + err(merged) + "] " + merged.waitFor());

            ProcessBuilder eb = new ProcessBuilder("/bin/sh", "-c", "echo \"$CHECK_A|${CHECK_B-unset}\"");
            eb.environment().put("CHECK_A", "set here");
            eb.environment().remove("CHECK_B");
            row("environment", out(eb.start()));
            ProcessBuilder only = new ProcessBuilder("/usr/bin/env");
            only.environment().clear();
            only.environment().put("ONLY", "this");
            row("environment cleared", out(only.start()));

            Path dir = Files.createDirectory(tmp.resolve("work dir"));
            Process pwd = new ProcessBuilder("/bin/sh", "-c", "pwd -P").directory(dir.toFile()).start();
            row("directory", out(pwd).equals(dir.toRealPath().toString()));

            // descriptors the parent holds open don't reach the child: ls lists
            // the same before and after the parent opens two more
            String before = out(sh("ls /dev/fd"));
            Path heldFile = Files.writeString(tmp.resolve("held"), "x");
            try (FileInputStream held = new FileInputStream(heldFile.toFile());
                 var ch = java.nio.channels.FileChannel.open(heldFile)) {
                row("descriptors", out(sh("ls /dev/fd")).equals(before) + " " + held.read());
            }
            Path in = tmp.resolve("in.txt"), outf = tmp.resolve("out.txt");
            Files.writeString(in, "from a file\n");
            Process fc = new ProcessBuilder("cat").redirectInput(in.toFile()).redirectOutput(outf.toFile()).start();
            row("file redirects", fc.waitFor() + " " + Files.readString(outf).strip() + " " + fc.getInputStream().read());
            new ProcessBuilder("/bin/sh", "-c", "echo appended").redirectOutput(Redirect.appendTo(outf.toFile())).start().waitFor();
            row("append", Files.readString(outf).replace("\n", "|"));
            Process dz = new ProcessBuilder("/bin/sh", "-c", "echo gone; echo kept 1>&2").redirectOutput(Redirect.DISCARD).start();
            row("discard", dz.getInputStream().read() + " " + err(dz) + " " + dz.waitFor());

            System.out.flush();
            Process inh = new ProcessBuilder("/bin/sh", "-c", "echo 'inherit: from the child'").redirectOutput(Redirect.INHERIT).start();
            inh.waitFor();
            row("inherit done", inh.exitValue());

            Process big = sh("i=0; while [ $i -lt 2000 ]; do echo 0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789; i=$((i+1)); done");
            byte[] all = big.getInputStream().readAllBytes();
            row("large output", all.length + " " + big.waitFor());

            Process slow = new ProcessBuilder("sleep", "30").start();
            long t0 = System.nanoTime();
            boolean done = slow.waitFor(200, TimeUnit.MILLISECONDS);
            row("waitFor timeout", done + " " + ((System.nanoTime() - t0) / 1_000_000 >= 150) + " " + slow.isAlive()
                + " " + fails(slow::exitValue));
            slow.destroy();
            row("destroy", slow.waitFor() + " " + slow.isAlive() + " " + slow.waitFor(1, TimeUnit.MILLISECONDS));
            Process stubborn = sh("trap '' TERM; echo ready; sleep 30");
            BufferedReader sr = new BufferedReader(new InputStreamReader(stubborn.getInputStream()));
            String ready = sr.readLine();
            stubborn.destroy();
            boolean ignored = !stubborn.waitFor(300, TimeUnit.MILLISECONDS);
            stubborn.destroyForcibly();
            row("destroyForcibly", ready + " " + ignored + " " + stubborn.waitFor() + " " + stubborn.toHandle().isAlive());

            Process later = sh("sleep 0.2; exit 7");
            CompletableFuture<Process> ex = later.onExit();
            row("onExit", ex.get(30, TimeUnit.SECONDS).exitValue() + " " + (ex.get() == later) + " "
                + later.toHandle().onExit().get(30, TimeUnit.SECONDS).isAlive());

            List<Process> pipeline = ProcessBuilder.startPipeline(List.of(
                new ProcessBuilder("printf", "banana\\napple\\ncherry\\n"),
                new ProcessBuilder("sort"),
                new ProcessBuilder("tr", "a-z", "A-Z")));
            Process last = pipeline.get(pipeline.size() - 1);
            row("pipeline", out(last).replace("\n", "|") + " " + pipeline.size());
            for (Process pp : pipeline) pp.waitFor();

            List<Process> many = new ArrayList<>();
            for (int i = 0; i < 6; i++) many.add(sh("exit " + i));
            int sum = 0;
            for (Process m : many) sum += m.waitFor();
            row("six at once", sum);

            Process rt = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "echo $0 $1", "zero", "one"});
            row("Runtime.exec", out(rt) + " " + rt.waitFor());

            row("no such program", fails(() -> new ProcessBuilder("no-such-program-for-the-check").start()));
            Path noexec = tmp.resolve("script.sh");
            Files.writeString(noexec, "#!/bin/sh\necho no\n");
            row("not executable", fails(() -> new ProcessBuilder(noexec.toString()).start()).replace(noexec.toString(), "<script>"));
            row("no such directory", fails(() -> new ProcessBuilder("echo").directory(new File("/no/such/dir/for/check")).start()));
            noexec.toFile().setExecutable(true);
            Process ok = new ProcessBuilder(noexec.toString()).start();
            row("made executable", out(ok) + " " + ok.waitFor());
            Path nosheb = tmp.resolve("plain");
            Files.writeString(nosheb, "echo without a shebang\n");
            nosheb.toFile().setExecutable(true);
            Process ns = new ProcessBuilder(nosheb.toString()).start();
            row("no shebang", out(ns) + " " + ns.waitFor());
            row("empty command", fails(() -> new ProcessBuilder().start()));
        } finally {
            try (var walk = Files.walk(tmp)) { walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); }
        }
    }
}
