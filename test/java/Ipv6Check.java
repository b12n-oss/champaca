import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

// IPv6 over the loopback interface: literals and their forms, a TCP server
// on ::1 and on the wildcard (dual stack: an IPv4 client reaches it, and the
// server sees the client as an Inet4Address), the NIO channels, UDP, and the
// loopback interface's addresses. Every value printed is the same on any
// machine with an IPv6 loopback: no host names, ports or scope ids that
// differ between systems, and no resolver lookups but loopback literals.
public class Ipv6Check {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable<?> c) {
        try { Object r = c.call(); return "no exception (" + r + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    static final InetAddress V6 = addr("::1");
    static final InetAddress V4 = addr("127.0.0.1");
    static InetAddress addr(String s) { try { return InetAddress.getByName(s); } catch (UnknownHostException e) { throw new RuntimeException(e); } }
    static String kind(InetAddress a) { return a.getClass().getSimpleName() + " " + a.getHostAddress(); }
    static ExecutorService pool = Executors.newCachedThreadPool();

    // one line back from a connection: what the server saw of the client
    static Future<String> echoPeer(ServerSocket ss) {
        return pool.submit(() -> {
            try (Socket c = ss.accept()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line = r.readLine();
                PrintWriter w = new PrintWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8), true);
                w.println(line + " back");
                return kind(c.getInetAddress()) + " local " + kind(c.getLocalAddress());
            }
        });
    }
    static String talk(InetAddress to, int port) throws IOException {
        try (Socket s = new Socket(to, port)) {
            PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            w.println("hi");
            String back = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return back + ", client local " + kind(s.getLocalAddress()) + " remote " + kind(s.getInetAddress());
        }
    }

    public static void main(String[] args) throws Exception {
        // literals
        row("literals", kind(V6) + " | " + kind(addr("0:0:0:0:0:0:0:1")) + " | " + kind(addr("[::1]"))
            + " | " + kind(addr("::ffff:127.0.0.1")) + " | " + kind(addr("::"))
            + " | " + kind(addr("fe80::1%1")) + " " + ((Inet6Address) addr("fe80::1%1")).getScopeId()
            + " | " + kind(addr("2001:db8::a:b")));
        row("predicates", V6.isLoopbackAddress() + " " + addr("::").isAnyLocalAddress() + " " + addr("fe80::1").isLinkLocalAddress()
            + " " + addr("ff02::1").isMulticastAddress() + " " + addr("fec0::1").isSiteLocalAddress()
            + " " + ((Inet6Address) addr("::1.2.3.4")).isIPv4CompatibleAddress());
        row("bytes", Arrays.toString(V6.getAddress()) + " " + addr("2001:db8::1").getAddress().length
            + " " + InetAddress.getByAddress(new byte[16]).getHostAddress()
            + " " + InetAddress.getByAddress(V4.getAddress()).getClass().getSimpleName());
        row("equality", V6.equals(addr("0::1")) + " " + (V6.hashCode() == addr("0::1").hashCode()) + " " + V6.equals(V4)
            + " " + new InetSocketAddress(V6, 80) + " " + new InetSocketAddress("::1", 80).getAddress().getHostAddress());
        row("loopback lookups", Arrays.stream(InetAddress.getAllByName("::1")).map(Ipv6Check::kind).toList()
            + " " + InetAddress.getLoopbackAddress().getClass().getSimpleName());

        // TCP on ::1
        try (ServerSocket ss = new ServerSocket(0, 50, V6)) {
            row("server on ::1", kind(ss.getInetAddress()) + " " + (ss.getLocalPort() > 0)
                + " " + ss.getLocalSocketAddress().equals(new InetSocketAddress(V6, ss.getLocalPort())));
            Future<String> peer = echoPeer(ss);
            row("client to ::1", talk(V6, ss.getLocalPort()) + " | server saw " + peer.get(10, TimeUnit.SECONDS));
            row("IPv4 client to ::1", fails(() -> { try (Socket s = new Socket()) { s.connect(new InetSocketAddress(V4, ss.getLocalPort()), 2000); return "connected"; } })
                .replaceAll(":.*", ""));
        }

        // TCP on the wildcard: dual stack, an IPv4 client is seen as an Inet4Address
        try (ServerSocket ss = new ServerSocket(0, 50)) {
            row("server on wildcard", kind(ss.getInetAddress()));
            Future<String> peer = echoPeer(ss);
            row("IPv4 client to wildcard", talk(V4, ss.getLocalPort()) + " | server saw " + peer.get(10, TimeUnit.SECONDS));
            peer = echoPeer(ss);
            row("IPv6 client to wildcard", talk(V6, ss.getLocalPort()) + " | server saw " + peer.get(10, TimeUnit.SECONDS));
        }

        // a socket bound to an IPv4 address, then connected over IPv4 only
        try (ServerSocket ss = new ServerSocket(0, 50, V4)) {
            row("server on 127.0.0.1", kind(ss.getInetAddress()));
            row("IPv6 client to 127.0.0.1", fails(() -> { try (Socket s = new Socket()) { s.connect(new InetSocketAddress(V6, ss.getLocalPort()), 2000); return "connected"; } })
                .replaceAll(":.*", ""));
        }

        // NIO channels
        try (ServerSocketChannel ssc = ServerSocketChannel.open().bind(new InetSocketAddress(V6, 0))) {
            InetSocketAddress at = (InetSocketAddress) ssc.getLocalAddress();
            try (SocketChannel sc = SocketChannel.open(at); SocketChannel acc = ssc.accept()) {
                sc.write(ByteBuffer.wrap("nio".getBytes(StandardCharsets.UTF_8)));
                ByteBuffer b = ByteBuffer.allocate(16);
                acc.read(b);
                row("channels on ::1", kind(at.getAddress()) + " " + new String(b.array(), 0, b.position(), StandardCharsets.UTF_8)
                    + " " + kind(((InetSocketAddress) sc.getRemoteAddress()).getAddress())
                    + " " + kind(((InetSocketAddress) acc.getRemoteAddress()).getAddress())
                    + " " + (((InetSocketAddress) acc.getRemoteAddress()).getPort() == ((InetSocketAddress) sc.getLocalAddress()).getPort()));
            }
        }
        try (ServerSocketChannel ssc = ServerSocketChannel.open().bind(new InetSocketAddress(0))) {
            int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            try (SocketChannel sc = SocketChannel.open(new InetSocketAddress(V4, port)); SocketChannel acc = ssc.accept()) {
                row("channels on wildcard", kind(((InetSocketAddress) ssc.getLocalAddress()).getAddress())
                    + " " + kind(((InetSocketAddress) acc.getRemoteAddress()).getAddress())
                    + " " + kind(((InetSocketAddress) acc.getLocalAddress()).getAddress()));
            }
        }
        try (SocketChannel sc = SocketChannel.open(StandardProtocolFamily.INET6)) {
            row("INET6 channel", sc.getLocalAddress() + " " + kind(((InetSocketAddress) sc.bind(new InetSocketAddress(V6, 0)).getLocalAddress()).getAddress()));
        }
        try (SocketChannel sc = SocketChannel.open(StandardProtocolFamily.INET)) {
            row("INET channel to ::1", fails(() -> sc.bind(new InetSocketAddress(V6, 0))).replaceAll(":.*", ""));
        }

        // UDP
        try (DatagramChannel a = DatagramChannel.open().bind(new InetSocketAddress(V6, 0));
             DatagramChannel b = DatagramChannel.open().bind(new InetSocketAddress(V6, 0))) {
            a.send(ByteBuffer.wrap("udp6".getBytes(StandardCharsets.UTF_8)), b.getLocalAddress());
            ByteBuffer buf = ByteBuffer.allocate(16);
            InetSocketAddress from = (InetSocketAddress) b.receive(buf);
            row("datagram on ::1", new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8) + " from " + kind(from.getAddress())
                + " " + (from.getPort() == ((InetSocketAddress) a.getLocalAddress()).getPort()));
        }
        try (DatagramChannel a = DatagramChannel.open().bind(new InetSocketAddress(0));
             DatagramChannel b = DatagramChannel.open().bind(new InetSocketAddress(V4, 0))) {
            a.send(ByteBuffer.wrap("udp4".getBytes(StandardCharsets.UTF_8)), b.getLocalAddress());
            ByteBuffer buf = ByteBuffer.allocate(16);
            InetSocketAddress from = (InetSocketAddress) b.receive(buf);
            row("datagram wildcard to 127.0.0.1", new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8) + " from " + kind(from.getAddress()));
            a.connect(b.getLocalAddress());
            row("connected datagram", kind(((InetSocketAddress) a.getRemoteAddress()).getAddress()) + " " + a.isConnected());
        }
        try (DatagramSocket ds = new DatagramSocket(0, V6)) {
            row("datagram socket on ::1", kind(ds.getLocalAddress()));
        }

        // the loopback interface carries ::1 (and its link-local address, whose
        // scope is the interface)
        NetworkInterface lo = NetworkInterface.getByInetAddress(V6);
        List<String> v6 = new ArrayList<>();
        for (InterfaceAddress ia : lo.getInterfaceAddresses())
            if (ia.getAddress() instanceof Inet6Address a6 && a6.isLoopbackAddress()) v6.add(a6.getClass().getSimpleName() + " ::1/" + ia.getNetworkPrefixLength() + " scoped " + (a6.getScopedInterface() != null));
        row("loopback interface", lo.isLoopback() + " " + v6 + " " + lo.equals(NetworkInterface.getByInetAddress(V4))
            + " " + Collections.list(lo.getInetAddresses()).stream().anyMatch(a -> a.equals(V6)));
        boolean linkLocalScoped = true;
        for (InetAddress a : Collections.list(lo.getInetAddresses()))
            if (a instanceof Inet6Address a6 && a6.isLinkLocalAddress())
                linkLocalScoped &= a6.getScopedInterface() != null && a6.getScopedInterface().equals(lo);
        row("link-local scope", linkLocalScoped);

        // reachability over IPv6
        row("reachable ::1", V6.isReachable(2000));

        pool.shutdownNow();
    }
}
