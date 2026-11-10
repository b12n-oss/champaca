import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

// TCP over the loopback interface: InetAddress, ServerSocket and Socket on
// NioSocketImpl (blocking, with timeouts), socket options, half-close, a
// large transfer, HttpURLConnection against a server in this process. Every
// value printed is the same on any machine: no host names, buffer sizes or
// error texts that differ between systems, and the only resolver lookups are
// loopback ones.
public class SocketCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable<?> c) {
        try { Object r = c.call(); return "no exception (" + r + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    static final InetAddress LOOP = InetAddress.getLoopbackAddress();
    static ExecutorService pool = Executors.newCachedThreadPool();

    // a server that runs one handler per accepted connection, on the pool
    interface Handler { void handle(Socket s) throws Exception; }
    static Future<?> serve(ServerSocket ss, Handler h) {
        return pool.submit(() -> { try (Socket c = ss.accept()) { h.handle(c); } return null; });
    }
    static PrintWriter writer(Socket s) throws IOException { return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true); }
    static BufferedReader reader(Socket s) throws IOException { return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)); }

    public static void main(String[] args) throws Exception {
        // addresses, parsed and looked up (loopback only; the local host is checked
        // for being there alone, since what it resolves to depends on the machine's
        // hosts file: a Debian box maps its name to 127.0.1.1, a Mac whose hostname
        // the resolver doesn't know gets its interfaces' addresses)
        row("addresses", LOOP + " " + InetAddress.getByName("127.0.0.1") + " " + InetAddress.getByName("localhost")
            + " " + LOOP.isLoopbackAddress() + " " + InetAddress.getByAddress(new byte[]{10, 1, 2, (byte) 254}).getHostAddress()
            + " " + (InetAddress.getLocalHost() != null) + " " + new InetSocketAddress(LOOP, 80) + " " + InetSocketAddress.createUnresolved("x", 1).isUnresolved()
            + " " + Arrays.toString(InetAddress.getAllByName("127.0.0.1")) + " " + InetAddress.getByName("127.0.0.1").getHostName()
            + " " + java.lang.management.ManagementFactory.getRuntimeMXBean().getName().matches("\\d+@.+"));
        row("unknown host", fails(() -> InetAddress.getByName("no.such.host.invalid")).replaceAll(":.*", ""));

        // a listening socket
        try (ServerSocket ss = new ServerSocket(0, 50, LOOP)) {
            row("server socket", (ss.getLocalPort() > 0) + " " + ss.isBound() + " " + ss.getInetAddress() + " " + ss.getReuseAddress()
                + " " + (ss.getReceiveBufferSize() > 0) + " " + ss.getLocalSocketAddress().equals(new InetSocketAddress(LOOP, ss.getLocalPort())));
            ss.setSoTimeout(100);
            row("accept timeout", fails(ss::accept));
            ss.setSoTimeout(0);

            // echo, with the connection's view from both ends
            Future<?> f = serve(ss, c -> {
                BufferedReader r = reader(c); PrintWriter w = writer(c);
                String line = r.readLine();
                w.println("echo " + line + " from " + (c.getLocalPort() == ss.getLocalPort()) + " " + c.getInetAddress() + " " + c.isConnected());
            });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                PrintWriter w = writer(s); BufferedReader r = reader(s);
                w.println("hi");
                row("echo", r.readLine() + " | " + s.isConnected() + " " + s.isBound() + " " + (s.getPort() == ss.getLocalPort()) + " " + (s.getLocalPort() > 0)
                    + " " + s.getInetAddress() + " " + s.getRemoteSocketAddress().equals(new InetSocketAddress(LOOP, ss.getLocalPort())) + " " + r.readLine());
                f.get();
                row("before close", s.isClosed() + " " + s.isInputShutdown());
            }

            // a client that reset its connection before the server accepted it: accept still answers
            for (int i = 0; i < 3; i++) {
                try (Socket s = new Socket(LOOP, ss.getLocalPort())) { s.setSoLinger(true, 0); }
            }
            StringBuilder aborted = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                // the peer address of a connection reset before accept is system-dependent (macOS's JVM shows 255.255.255.255)
                try (Socket a = ss.accept()) { aborted.append(a.isConnected()).append(' ').append(a.getInetAddress() != null).append(' '); }
                catch (IOException e) { aborted.append(e.getClass().getName()).append(' '); }
            }
            row("reset before accept", aborted.toString().trim());

            // urgent data is sent (the receiver drops it unless OOBINLINE is on)
            f = serve(ss, c -> { c.getInputStream().read(); });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                s.sendUrgentData(7);
                s.getOutputStream().write(1);
                row("urgent data", "sent " + s.getOOBInline());
                f.get();
            }

            // options
            f = serve(ss, c -> { c.getInputStream().read(); });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                s.setTcpNoDelay(true); boolean nd1 = s.getTcpNoDelay(); s.setTcpNoDelay(false);
                s.setKeepAlive(true); boolean ka = s.getKeepAlive();
                s.setSoLinger(true, 7); int l1 = s.getSoLinger(); s.setSoLinger(false, 0);
                s.setSoTimeout(1234);
                s.setSendBufferSize(65536); s.setReceiveBufferSize(65536);
                row("options", nd1 + " " + s.getTcpNoDelay() + " " + ka + " " + l1 + " " + s.getSoLinger() + " " + s.getSoTimeout()
                    + " " + (s.getSendBufferSize() > 0) + " " + (s.getReceiveBufferSize() > 0) + " " + s.getOOBInline() + " " + s.getReuseAddress());
                s.getOutputStream().write(1);
                f.get();
            }

            // a read timeout, then data after it
            f = serve(ss, c -> { c.getInputStream().read(); c.getOutputStream().write("late\n".getBytes()); });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                s.setSoTimeout(150);
                BufferedReader r = reader(s);
                String timedOut = fails(r::readLine);
                s.getOutputStream().write('\n');
                row("read timeout", timedOut + " | " + r.readLine());
                f.get();
            }

            // half close: the server sees the end of the stream, the client still reads
            f = serve(ss, c -> {
                InputStream in = c.getInputStream();
                int n = 0; while (in.read() != -1) n++;
                writer(c).println("got " + n + " then eof " + in.read());
            });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                s.getOutputStream().write(new byte[]{1, 2, 3});
                s.shutdownOutput();
                row("half close", s.isOutputShutdown() + " " + reader(s).readLine() + " " + fails(() -> { s.getOutputStream().write(9); return "wrote"; }).replaceAll(":.*", ""));
                f.get();
            }

            // available, after the server has written
            f = serve(ss, c -> { c.getOutputStream().write(new byte[]{1, 2, 3, 4, 5}); c.getOutputStream().flush(); c.getInputStream().read(); });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                InputStream in = s.getInputStream();
                int avail = 0;
                for (int i = 0; i < 200 && avail < 5; i++) { avail = in.available(); if (avail < 5) Thread.sleep(10); }
                byte[] b = new byte[avail]; int n = in.read(b);
                row("available", avail + " " + n + " " + Arrays.toString(b));
                s.getOutputStream().write(0);
                f.get();
            }

            // a large transfer both ways, checked by CRC
            byte[] big = new byte[1 << 20];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31 + (i >> 8));
            CRC32 sent = new CRC32(); sent.update(big);
            Future<Long> echoed = pool.submit(() -> {
                try (Socket c = ss.accept()) {
                    InputStream in = c.getInputStream(); OutputStream out = c.getOutputStream();
                    byte[] buf = new byte[8192]; long total = 0; int n;
                    while ((n = in.read(buf)) != -1) { total += n; out.write(buf, 0, n); }
                    return total;
                }
            });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                Future<Long> back = pool.submit(() -> {
                    InputStream in = s.getInputStream(); CRC32 crc = new CRC32(); byte[] buf = new byte[8192]; long total = 0; int n;
                    while (total < big.length && (n = in.read(buf)) != -1) { crc.update(buf, 0, n); total += n; }
                    return crc.getValue();
                });
                OutputStream out = s.getOutputStream();
                for (int off = 0; off < big.length; off += 65536) out.write(big, off, 65536);
                out.flush();
                long crc = back.get();
                s.shutdownOutput();
                row("large transfer", echoed.get() + " " + (crc == sent.getValue()));
            }

            // the server's view of a client that closed
            f = serve(ss, c -> {
                InputStream in = c.getInputStream();
                int first = in.read(), second = in.read();
                writer(c).println(first + " " + second);
            });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) { s.getOutputStream().write(42); s.shutdownOutput(); row("eof seen", reader(s).readLine()); f.get(); }

            // writing to a peer that is gone: an exception, never the end of the process
            f = serve(ss, c -> { });
            try (Socket s = new Socket(LOOP, ss.getLocalPort())) {
                f.get();
                Thread.sleep(50);
                String outcome = "no exception";
                try { for (int i = 0; i < 200; i++) { s.getOutputStream().write(new byte[8192]); } }
                // which of the two the system gives depends on timing: the JVM has shown both
                catch (IOException e) { outcome = e.getClass().getName() + " " + Set.of("Broken pipe", "Connection reset by peer").contains(e.getMessage()); }
                row("peer gone", outcome);
            }

            // HTTP through HttpURLConnection, against a one-request server here
            f = serve(ss, c -> {
                BufferedReader r = reader(c);
                String request = r.readLine(); String line; String agentSeen = "none";
                while ((line = r.readLine()) != null && !line.isEmpty()) if (line.startsWith("Host: ")) agentSeen = line.replaceAll(":\\d+$", ":port");
                byte[] body = ("you asked " + request + " with " + agentSeen).getBytes(StandardCharsets.UTF_8);
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nX-Served-By: SocketCheck\r\nConnection: close\r\n\r\n").getBytes());
                out.write(body); out.flush();
            });
            URL url = new URL("http://127.0.0.1:" + ss.getLocalPort() + "/path?q=1");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("User-Agent", "SocketCheck");
            String body = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            row("http", c.getResponseCode() + " " + c.getResponseMessage() + " | " + c.getContentType() + " | " + c.getHeaderField("X-Served-By") + " | " + c.getContentLength() + " | " + body);
            f.get();
            f = serve(ss, conn -> {
                reader(conn).readLine();
                conn.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 4\r\nConnection: close\r\n\r\ngone".getBytes());
            });
            HttpURLConnection c2 = (HttpURLConnection) new URL("http://127.0.0.1:" + ss.getLocalPort() + "/missing").openConnection();
            row("http 404", c2.getResponseCode() + " " + fails(c2::getInputStream).replaceAll(":.*", "") + " " + new String(c2.getErrorStream().readAllBytes()));
            f.get();
        }

        // NIO channels in blocking mode run on the same natives
        try (java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(LOOP, 0));
            Future<String> served = pool.submit(() -> {
                try (java.nio.channels.SocketChannel c = ssc.accept()) {
                    java.nio.ByteBuffer in = java.nio.ByteBuffer.allocate(64);
                    int n = c.read(in); in.flip();
                    String got = StandardCharsets.UTF_8.decode(in).toString();
                    c.write(java.nio.ByteBuffer.wrap(("channel " + got).getBytes(StandardCharsets.UTF_8)));
                    return n + " " + c.getRemoteAddress().equals(c.getRemoteAddress()) + " " + (c.socket().getPort() > 0);
                }
            });
            try (java.nio.channels.SocketChannel sc = java.nio.channels.SocketChannel.open(new InetSocketAddress(LOOP, ((InetSocketAddress) ssc.getLocalAddress()).getPort()))) {
                sc.write(java.nio.ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));
                java.nio.ByteBuffer back = java.nio.ByteBuffer.allocateDirect(64);
                int n = sc.read(back); back.flip();
                row("channels", sc.isBlocking() + " " + sc.isConnected() + " " + n + " " + StandardCharsets.UTF_8.decode(back) + " | " + served.get()
                    + " " + sc.getOption(java.net.StandardSocketOptions.TCP_NODELAY) + " " + (sc.getLocalAddress() instanceof InetSocketAddress));
            }
        }

        // closing from another thread wakes a thread blocked in accept or read
        try (ServerSocket ss = new ServerSocket(0, 50, LOOP)) {
            CountDownLatch entering = new CountDownLatch(1);
            Future<String> blocked = pool.submit(() -> { entering.countDown(); return fails(ss::accept); });
            entering.await(); Thread.sleep(100);
            ss.close();
            row("close wakes accept", blocked.get(10, TimeUnit.SECONDS) + " " + ss.isClosed());
        }
        try (ServerSocket ss = new ServerSocket(0, 50, LOOP)) {
            Future<?> f = serve(ss, c -> { c.getInputStream().read(); });
            Socket s = new Socket(LOOP, ss.getLocalPort());
            CountDownLatch entering = new CountDownLatch(1);
            Future<String> blocked = pool.submit(() -> { entering.countDown(); return fails(() -> s.getInputStream().read()); });
            entering.await(); Thread.sleep(100);
            s.close();
            row("close wakes read", blocked.get(10, TimeUnit.SECONDS) + " " + s.isClosed());
            f.get();
        }
        // (the channel rows wait half a second for the thread to be inside its call: a close
        // that comes first is a ClosedChannelException rather than an asynchronous one)
        try (java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(LOOP, 0));
            CountDownLatch entering = new CountDownLatch(1);
            Future<String> blocked = pool.submit(() -> { entering.countDown(); return fails(ssc::accept); });
            entering.await(); Thread.sleep(500);
            ssc.close();
            row("close wakes channel accept", blocked.get(10, TimeUnit.SECONDS));
        }
        // a FileChannel read blocked on a FIFO is woken the same way. The FIFO is the one jolt test
        // makes (target/blocking-io.fifo), assumed empty, as the programs before this one leave it;
        // a run without it says so instead of pretending
        java.nio.file.Path fifo = java.nio.file.Path.of("target/blocking-io.fifo");
        if (java.nio.file.Files.exists(fifo)) {
            java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(fifo, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
            CountDownLatch entering = new CountDownLatch(1);
            Future<String> blocked = pool.submit(() -> { entering.countDown(); return fails(() -> fc.read(java.nio.ByteBuffer.allocate(8))); });
            entering.await(); Thread.sleep(500);
            fc.close();
            row("close wakes file channel read", blocked.get(10, TimeUnit.SECONDS) + " " + fc.isOpen());
        } else {
            row("close wakes file channel read", "skipped: no target/blocking-io.fifo (run under jolt test)");
        }
        try (java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(LOOP, 0));
            Future<java.nio.channels.SocketChannel> accepted = pool.submit(ssc::accept);
            java.nio.channels.SocketChannel sc = java.nio.channels.SocketChannel.open(ssc.getLocalAddress());
            accepted.get();
            CountDownLatch entering = new CountDownLatch(1);
            Future<String> blocked = pool.submit(() -> { entering.countDown(); return fails(() -> sc.read(java.nio.ByteBuffer.allocate(8))); });
            entering.await(); Thread.sleep(500);
            sc.close();
            row("close wakes channel read", blocked.get(10, TimeUnit.SECONDS) + " " + sc.isOpen());
            // and an interrupt of the blocked thread closes the channel it is reading
            java.nio.channels.SocketChannel sc2 = java.nio.channels.SocketChannel.open(ssc.getLocalAddress());
            accepted = pool.submit(ssc::accept); accepted.get();
            CountDownLatch entering2 = new CountDownLatch(1);
            Thread[] reader = new Thread[1];
            Future<String> interrupted = pool.submit(() -> {
                reader[0] = Thread.currentThread(); entering2.countDown();
                String r = fails(() -> sc2.read(java.nio.ByteBuffer.allocate(8)));
                return r + " " + Thread.currentThread().isInterrupted();
            });
            entering2.await(); Thread.sleep(100);
            reader[0].interrupt();
            row("interrupt wakes channel read", interrupted.get(10, TimeUnit.SECONDS) + " " + sc2.isOpen());
        }

        // nobody listening: the port the server just released
        int freed;
        try (ServerSocket ss = new ServerSocket(0, 50, LOOP)) { freed = ss.getLocalPort(); }
        row("refused", fails(() -> new Socket(LOOP, freed)) + " | " + fails(() -> { Socket s = new Socket(); s.connect(new InetSocketAddress(LOOP, freed), 500); return s; }));
        row("bind twice", fails(() -> {
            try (ServerSocket a = new ServerSocket(); ServerSocket b = new ServerSocket()) {
                a.setReuseAddress(false); b.setReuseAddress(false);
                a.bind(new InetSocketAddress(LOOP, 0)); b.bind(new InetSocketAddress(LOOP, a.getLocalPort())); return b;
            }
        }));
        row("unconnected", fails(() -> new Socket().getInputStream()) + " | " + new Socket().isConnected() + " " + new Socket().getLocalPort() + " " + new Socket().getPort());
        pool.shutdown();
    }
}
