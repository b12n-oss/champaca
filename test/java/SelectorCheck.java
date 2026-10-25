import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

// Selectors over the loopback interface: readiness for accept, connect, read
// and write, select with and without a timeout, wakeup, interrupt and close
// from another thread, keys cancelled and channels closed, a Pipe, a
// non-blocking echo server driven by one selector, and java.net.http's
// HttpClient (its SelectorManager thread) against a server in this process.
// Every value printed is the same on any machine: no provider class names,
// which differ between macOS (KQueue) and Linux (EPoll).
public class SelectorCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable<?> c) {
        try { Object r = c.call(); return "no exception (" + r + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    static final InetAddress LOOP = InetAddress.getLoopbackAddress();
    static ExecutorService pool = Executors.newCachedThreadPool();
    static ByteBuffer bytes(String s) { return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)); }
    static String text(ByteBuffer b) { b.flip(); return StandardCharsets.UTF_8.decode(b).toString(); }
    static String ops(int o) {
        List<String> l = new ArrayList<>();
        if ((o & SelectionKey.OP_ACCEPT) != 0) l.add("accept");
        if ((o & SelectionKey.OP_CONNECT) != 0) l.add("connect");
        if ((o & SelectionKey.OP_READ) != 0) l.add("read");
        if ((o & SelectionKey.OP_WRITE) != 0) l.add("write");
        return String.join("+", l);
    }
    // the ready ops of each selected key, in registration order of the names given
    static String selected(Selector sel, Map<SelectableChannel, String> names) {
        TreeMap<String, String> m = new TreeMap<>();
        for (SelectionKey k : sel.selectedKeys()) m.put(names.get(k.channel()), ops(k.readyOps()));
        sel.selectedKeys().clear();
        return m.toString();
    }

    public static void main(String[] args) throws Exception {
        // an empty selector
        try (Selector sel = Selector.open()) {
            row("open", sel.isOpen() + " " + sel.keys().size() + " " + sel.selectNow()
                + " " + (sel.provider() == java.nio.channels.spi.SelectorProvider.provider()));
            long t0 = System.nanoTime();
            int n = sel.select(150);
            row("select timeout", n + " " + ((System.nanoTime() - t0) / 1_000_000 >= 100));
        }

        // accept, connect, write and read readiness on one selector
        try (Selector sel = Selector.open(); ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(LOOP, 0));
            ssc.configureBlocking(false);
            SelectionKey ak = ssc.register(sel, SelectionKey.OP_ACCEPT);
            row("register", ops(ak.interestOps()) + " " + ak.isValid() + " " + (ak.selector() == sel) + " " + sel.keys().size()
                + " " + fails(() -> { ssc.configureBlocking(true); return null; }));
            SocketChannel client = SocketChannel.open();
            client.configureBlocking(false);
            boolean now = client.connect(ssc.getLocalAddress());
            SelectionKey ck = client.register(sel, SelectionKey.OP_CONNECT);
            Map<SelectableChannel, String> names = new HashMap<>();
            names.put(ssc, "server"); names.put(client, "client");
            SocketChannel accepted = null;
            boolean connected = now;
            StringBuilder seen = new StringBuilder();
            long deadline = System.currentTimeMillis() + 10_000;
            while ((accepted == null || !connected) && System.currentTimeMillis() < deadline) {
                sel.select(1000);
                for (SelectionKey k : sel.selectedKeys()) {
                    if (k.isAcceptable()) { accepted = ssc.accept(); seen.append("accept "); }
                    if (k.isConnectable()) { connected = client.finishConnect(); seen.append("connect "); k.interestOps(0); }
                }
                sel.selectedKeys().clear();
            }
            row("accept and connect", (accepted != null) + " " + connected + " " + client.isConnected() + " " + seen.toString().contains("accept")
                + " " + (now || seen.toString().contains("connect")));
            accepted.configureBlocking(false);
            SelectionKey rk = accepted.register(sel, SelectionKey.OP_READ);
            names.put(accepted, "accepted");
            ak.cancel();
            ck.interestOps(SelectionKey.OP_WRITE);
            row("writable", sel.select(1000) + " " + selected(sel, names));
            client.write(bytes("ping"));
            ck.interestOps(0);
            sel.select(1000);
            ByteBuffer b = ByteBuffer.allocate(64);
            int got = accepted.read(b);
            row("readable", selected(sel, names) + " " + got + " " + text(b) + " " + rk.isReadable());
            b.clear();
            row("nothing to read", accepted.read(b) + " " + sel.selectNow());
            client.close();
            sel.select(1000);
            b.clear();
            row("peer closed", selected(sel, names) + " " + accepted.read(b) + " " + ck.isValid());
            accepted.close();
            sel.selectNow();
            row("closed channel", rk.isValid() + " " + sel.keys().size() + " " + ak.isValid());
            row("cancelled key", fails(() -> ak.interestOps(SelectionKey.OP_ACCEPT)));
            SocketChannel gone = accepted;
            row("closed register", fails(() -> gone.register(sel, SelectionKey.OP_READ)));
            row("blocking register", fails(() -> { try (SocketChannel bc = SocketChannel.open()) { return bc.register(sel, SelectionKey.OP_READ); } }));
            row("bad ops", fails(() -> ssc.register(sel, SelectionKey.OP_READ)));
        }

        // a pipe, its source selected for read
        try (Selector sel = Selector.open()) {
            Pipe p = Pipe.open();
            p.source().configureBlocking(false);
            p.source().register(sel, SelectionKey.OP_READ);
            row("pipe idle", sel.selectNow());
            p.sink().write(bytes("through the pipe"));
            int n = sel.select(1000);
            ByteBuffer b = ByteBuffer.allocate(64);
            p.source().read(b);
            row("pipe", n + " " + text(b) + " " + p.sink().isOpen());
            p.sink().close();
            sel.select(1000);
            b.clear();
            row("pipe closed", p.source().read(b));
            p.source().close();
        }

        // wakeup, interrupt and close from another thread
        try (Selector sel = Selector.open()) {
            sel.wakeup();
            row("wakeup before", sel.select() + " " + sel.selectNow());
            Future<String> f = pool.submit(() -> {
                long t0 = System.nanoTime();
                int n = sel.select();
                return n + " " + ((System.nanoTime() - t0) / 1_000_000 >= 100);
            });
            Thread.sleep(200);
            sel.wakeup();
            row("wakeup", f.get(10, TimeUnit.SECONDS));
            Thread[] th = new Thread[1];
            Future<String> g = pool.submit(() -> {
                th[0] = Thread.currentThread();
                int n = sel.select();
                return n + " " + Thread.interrupted();
            });
            Thread.sleep(200);
            th[0].interrupt();
            row("interrupt", g.get(10, TimeUnit.SECONDS));
            Future<String> h = pool.submit(() -> {
                int n = sel.select();
                return n + " " + sel.isOpen();
            });
            Thread.sleep(200);
            sel.close();
            row("close", h.get(10, TimeUnit.SECONDS));
            row("closed select", fails(() -> sel.select(1)) + " " + fails(sel::keys));
        }

        // select with an action, the JDK 11 form
        try (Selector sel = Selector.open(); ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(LOOP, 0));
            ssc.configureBlocking(false);
            ssc.register(sel, SelectionKey.OP_ACCEPT);
            try (Socket s = new Socket(LOOP, ((InetSocketAddress) ssc.getLocalAddress()).getPort())) {
                List<String> acts = new ArrayList<>();
                int n = sel.select(k -> { acts.add(ops(k.readyOps())); try { ssc.accept().close(); } catch (IOException e) { acts.add(e.toString()); } }, 1000);
                row("select action", n + " " + acts);
            }
        }

        // an echo server on one selector, five clients on blocking sockets
        try (Selector sel = Selector.open(); ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(LOOP, 0));
            ssc.configureBlocking(false);
            ssc.register(sel, SelectionKey.OP_ACCEPT);
            int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            List<Future<String>> clients = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int id = i;
                clients.add(pool.submit(() -> {
                    try (Socket s = new Socket(LOOP, port)) {
                        PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        w.println("client " + id);
                        return r.readLine();
                    }
                }));
            }
            int answered = 0;
            long deadline = System.currentTimeMillis() + 20_000;
            while (answered < 5 && System.currentTimeMillis() < deadline) {
                sel.select(1000);
                Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next(); it.remove();
                    if (k.isAcceptable()) {
                        SocketChannel c = ssc.accept();
                        if (c != null) { c.configureBlocking(false); c.register(sel, SelectionKey.OP_READ, ByteBuffer.allocate(256)); }
                    } else if (k.isReadable()) {
                        SocketChannel c = (SocketChannel) k.channel();
                        ByteBuffer b = (ByteBuffer) k.attachment();
                        if (c.read(b) < 0) { c.close(); continue; }
                        String sofar = new String(b.array(), 0, b.position(), StandardCharsets.UTF_8);
                        if (sofar.endsWith("\n")) {
                            c.write(bytes("echo " + sofar));
                            answered++;
                            c.close();
                        }
                    }
                }
            }
            List<String> got = new ArrayList<>();
            for (Future<String> f : clients) got.add(f.get(10, TimeUnit.SECONDS));
            row("echo server", answered + " " + got);
        }

        // java.net.http's HttpClient against a server in this process
        try (ServerSocket ss = new ServerSocket(0, 50, LOOP)) {
            Future<String> server = pool.submit(() -> {
                StringBuilder seen = new StringBuilder();
                for (int i = 0; i < 2; i++) {
                    try (Socket c = ss.accept()) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
                        String first = r.readLine();
                        int length = 0;
                        for (String h; (h = r.readLine()) != null && !h.isEmpty(); )
                            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
                        char[] body = new char[length];
                        for (int off = 0; off < length; ) off += r.read(body, off, length - off);
                        seen.append(first).append(length > 0 ? " [" + new String(body) + "]" : "").append(";");
                        String reply = first.startsWith("POST") ? "got " + new String(body) : "hello from the server";
                        OutputStream o = c.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nX-Check: yes\r\nContent-Length: " + reply.length()
                                 + "\r\nConnection: close\r\n\r\n" + reply).getBytes(StandardCharsets.ISO_8859_1));
                        o.flush();
                    }
                }
                return seen.toString();
            });
            URI base = URI.create("http://127.0.0.1:" + ss.getLocalPort());
            HttpClient hc = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(10)).build();
            HttpResponse<String> r = hc.send(HttpRequest.newBuilder(base.resolve("/hello")).timeout(java.time.Duration.ofSeconds(20)).build(),
                                             HttpResponse.BodyHandlers.ofString());
            row("http get", r.statusCode() + " " + r.body() + " " + r.headers().firstValue("x-check").orElse("-") + " " + r.version());
            HttpResponse<String> p = hc.sendAsync(HttpRequest.newBuilder(base.resolve("/echo")).timeout(java.time.Duration.ofSeconds(20))
                                                  .POST(HttpRequest.BodyPublishers.ofString("a body")).build(),
                                                  HttpResponse.BodyHandlers.ofString()).get(30, TimeUnit.SECONDS);
            row("http post", p.statusCode() + " " + p.body());
            row("http server saw", server.get(10, TimeUnit.SECONDS));
            hc.shutdownNow();
            row("http closed", hc.awaitTermination(java.time.Duration.ofSeconds(10)) + " " + hc.isTerminated());
        }
        try (ServerSocket ss = new ServerSocket(0, 50, LOOP)) {
            int port = ss.getLocalPort();
            ss.close();
            HttpClient hc = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            row("http refused", fails(() -> hc.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                                                  HttpResponse.BodyHandlers.ofString())).replaceAll(":.*", ""));
            hc.shutdownNow();
        }
        pool.shutdown();
    }
}
