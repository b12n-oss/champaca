import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

// UDP over the loopback interface (DatagramSocket and DatagramChannel, blocking,
// non-blocking and under a selector), the network interfaces as far as every
// machine agrees (the loopback interface and its IPv4 address), and
// isReachable. Every value printed is the same on any machine: no interface
// names, indexes, MTUs or hardware addresses, which differ between macOS (lo0)
// and Linux (lo), and no IPv6 addresses, which champaca doesn't offer.
public class DatagramCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable<?> c) {
        try { Object r = c.call(); return "no exception (" + r + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    static final InetAddress LOOP = InetAddress.getLoopbackAddress();
    static ByteBuffer bytes(String s) { return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)); }
    static String text(ByteBuffer b) { b.flip(); return StandardCharsets.UTF_8.decode(b).toString(); }
    static String text(DatagramPacket p) { return new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8); }
    static DatagramPacket packet(String s, SocketAddress to) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new DatagramPacket(b, b.length, to);
    }

    public static void main(String[] args) throws Exception {
        // a round trip between two DatagramSockets
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress(LOOP, 0));
             DatagramSocket b = new DatagramSocket(new InetSocketAddress(LOOP, 0))) {
            row("bound", a.isBound() + " " + a.getLocalAddress().getHostAddress() + " " + (a.getLocalPort() > 0)
                + " " + a.isConnected() + " " + a.getRemoteSocketAddress());
            a.send(packet("hello", b.getLocalSocketAddress()));
            DatagramPacket p = new DatagramPacket(new byte[100], 100);
            b.receive(p);
            row("round trip", text(p) + " " + p.getLength() + " " + p.getAddress().getHostAddress()
                + " " + (p.getPort() == a.getLocalPort()) + " " + p.getSocketAddress().equals(a.getLocalSocketAddress()));

            // a datagram bigger than the buffer is cut to it
            a.send(packet("0123456789", b.getLocalSocketAddress()));
            DatagramPacket small = new DatagramPacket(new byte[4], 4);
            b.receive(small);
            row("truncated", text(small) + " " + small.getLength());

            // an offset into the buffer, and an empty datagram
            a.send(packet("xyz", b.getLocalSocketAddress()));
            byte[] buf = new byte[10];
            DatagramPacket off = new DatagramPacket(buf, 3, 5);
            b.receive(off);
            row("offset", text(off) + " " + off.getOffset() + " " + off.getLength() + " " + buf[3] + " " + buf[0]);
            a.send(new DatagramPacket(new byte[0], 0, b.getLocalSocketAddress()));
            DatagramPacket empty = new DatagramPacket(new byte[10], 10);
            b.receive(empty);
            row("empty", empty.getLength() + " " + (empty.getPort() == a.getLocalPort()));

            // big datagrams
            for (int n : new int[] {8000, 60000}) {
                byte[] big = new byte[n];
                for (int i = 0; i < n; i++) big[i] = (byte) (i * 7);
                a.send(new DatagramPacket(big, n, b.getLocalSocketAddress()));
                DatagramPacket r = new DatagramPacket(new byte[65536], 65536);
                b.receive(r);
                row("big " + n, r.getLength() + " " + Arrays.equals(big, Arrays.copyOf(r.getData(), r.getLength())));
            }

            // a receive that times out
            b.setSoTimeout(200);
            long t0 = System.nanoTime();
            row("timeout", fails(() -> { b.receive(new DatagramPacket(new byte[10], 10)); return null; })
                + " " + ((System.nanoTime() - t0) / 1_000_000 >= 150) + " " + b.getSoTimeout());
        }

        // a connected DatagramSocket: send without an address, receive only from the peer
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress(LOOP, 0));
             DatagramSocket b = new DatagramSocket(new InetSocketAddress(LOOP, 0));
             DatagramSocket c = new DatagramSocket(new InetSocketAddress(LOOP, 0))) {
            b.connect(a.getLocalSocketAddress());
            row("connected", b.isConnected() + " " + b.getRemoteSocketAddress().equals(a.getLocalSocketAddress())
                + " " + (b.getPort() == a.getLocalPort()) + " " + b.getInetAddress().getHostAddress());
            byte[] hi = "to a".getBytes(StandardCharsets.UTF_8);
            b.send(new DatagramPacket(hi, hi.length));
            DatagramPacket p = new DatagramPacket(new byte[20], 20);
            a.receive(p);
            row("connected send", text(p) + " " + p.getSocketAddress().equals(b.getLocalSocketAddress()));
            row("wrong address", fails(() -> { b.send(packet("x", c.getLocalSocketAddress())); return null; }));
            c.send(packet("from c", b.getLocalSocketAddress()));
            a.send(packet("from a", b.getLocalSocketAddress()));
            DatagramPacket q = new DatagramPacket(new byte[20], 20);
            b.setSoTimeout(2000);
            b.receive(q);
            row("connected receive", text(q));
            b.disconnect();
            row("disconnected", b.isConnected() + " " + b.getRemoteSocketAddress() + " " + b.getPort());
            c.send(packet("from c again", b.getLocalSocketAddress()));
            DatagramPacket r = new DatagramPacket(new byte[20], 20);
            b.receive(r);
            row("after disconnect", text(r) + " " + r.getSocketAddress().equals(c.getLocalSocketAddress()));
        }

        // a datagram sent to a port nobody holds: the connected socket hears the refusal
        try (DatagramChannel gone = DatagramChannel.open(); DatagramChannel ch = DatagramChannel.open()) {
            gone.bind(new InetSocketAddress(LOOP, 0));
            SocketAddress dead = gone.getLocalAddress();
            gone.close();
            ch.connect(dead);
            ch.write(bytes("anyone?"));
            ch.socket().setSoTimeout(2000);
            Thread.sleep(100);
            row("unreachable", fails(() -> ch.read(ByteBuffer.allocate(10))));
            ch.write(bytes("again?"));
            Thread.sleep(100);
            row("unreachable receive", fails(() -> ch.receive(ByteBuffer.allocate(10))));
        }

        // DatagramChannel: send and receive, read and write once connected
        try (DatagramChannel a = DatagramChannel.open(); DatagramChannel b = DatagramChannel.open()) {
            a.bind(new InetSocketAddress(LOOP, 0));
            b.bind(new InetSocketAddress(LOOP, 0));
            int sent = a.send(bytes("channel"), b.getLocalAddress());
            ByteBuffer in = ByteBuffer.allocate(50);
            SocketAddress from = b.receive(in);
            row("channel", sent + " " + text(in) + " " + from.equals(a.getLocalAddress()));
            ByteBuffer direct = ByteBuffer.allocateDirect(50);
            direct.put("direct".getBytes(StandardCharsets.UTF_8)).flip();
            a.send(direct, b.getLocalAddress());
            ByteBuffer din = ByteBuffer.allocateDirect(50);
            b.receive(din);
            row("direct buffers", text(din) + " " + direct.remaining());
            a.connect(b.getLocalAddress());
            b.connect(a.getLocalAddress());
            row("channel connected", a.isConnected() + " " + a.getRemoteAddress().equals(b.getLocalAddress()));
            a.write(bytes("write"));
            ByteBuffer rd = ByteBuffer.allocate(50);
            row("channel read", b.read(rd) + " " + text(rd));
            a.write(new ByteBuffer[] {bytes("ga"), bytes("ther")});
            ByteBuffer s1 = ByteBuffer.allocate(3), s2 = ByteBuffer.allocate(10);
            long got = b.read(new ByteBuffer[] {s1, s2});
            row("scatter gather", got + " " + text(s1) + "|" + text(s2));
            a.disconnect();
            row("channel disconnected", a.isConnected() + " " + a.getRemoteAddress()
                + " " + fails(() -> a.write(bytes("no"))));
        }

        // a channel bound to the wildcard address keeps its local address across
        // connect and disconnect, which the JDK repairs where the kernel doesn't
        try (DatagramChannel w = DatagramChannel.open(); DatagramChannel peer = DatagramChannel.open()) {
            w.bind(new InetSocketAddress(0));
            peer.bind(new InetSocketAddress(LOOP, 0));
            SocketAddress before = w.getLocalAddress();
            w.connect(peer.getLocalAddress());
            String during = ((InetSocketAddress) w.getLocalAddress()).getAddress().getHostAddress();
            w.disconnect();
            w.send(bytes("still here"), peer.getLocalAddress());
            ByteBuffer in = ByteBuffer.allocate(20);
            SocketAddress from = peer.receive(in);
            row("wildcard disconnect", during + " " + before.equals(w.getLocalAddress()) + " " + text(in)
                + " " + (((InetSocketAddress) from).getPort() == ((InetSocketAddress) before).getPort()));
        }

        // an empty datagram read through a connected channel
        try (DatagramChannel a = DatagramChannel.open(); DatagramChannel b = DatagramChannel.open()) {
            a.bind(new InetSocketAddress(LOOP, 0));
            b.bind(new InetSocketAddress(LOOP, 0));
            b.connect(a.getLocalAddress());
            a.send(ByteBuffer.allocate(0), b.getLocalAddress());
            row("empty read", b.read(ByteBuffer.allocate(10)));
        }

        // non-blocking: nothing waiting is null, then a selector sees the datagram
        try (Selector sel = Selector.open(); DatagramChannel a = DatagramChannel.open(); DatagramChannel b = DatagramChannel.open()) {
            a.bind(new InetSocketAddress(LOOP, 0));
            b.bind(new InetSocketAddress(LOOP, 0));
            b.configureBlocking(false);
            row("nothing waiting", b.receive(ByteBuffer.allocate(10)));
            SelectionKey k = b.register(sel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            int n = sel.selectNow();
            row("writable", n + " " + k.isWritable() + " " + k.isReadable());
            sel.selectedKeys().clear();
            k.interestOps(SelectionKey.OP_READ);
            a.send(bytes("ping"), b.getLocalAddress());
            int m = sel.select(2000);
            ByteBuffer in = ByteBuffer.allocate(10);
            SocketAddress from = b.receive(in);
            row("selected", m + " " + k.isReadable() + " " + text(in) + " " + from.equals(a.getLocalAddress()));
            sel.selectedKeys().clear();
            row("drained", sel.selectNow() + " " + b.receive(ByteBuffer.allocate(10)));
        }

        // options
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.setTrafficClass(0x10);
            s.setReceiveBufferSize(32768);
            s.setSendBufferSize(32768);
            s.bind(new InetSocketAddress(LOOP, 0));
            row("options", s.getReuseAddress() + " " + s.getBroadcast() + " " + s.getTrafficClass()
                + " " + (s.getReceiveBufferSize() >= 32768) + " " + (s.getSendBufferSize() >= 32768));
            s.setBroadcast(false);
            row("broadcast off", s.getBroadcast());
            // IP_DONTFRAGMENT and SO_INCOMING_NAPI_ID are jdk.net options, offered
            // by the JVM and not by champaca
            row("supported", new TreeSet<>(s.supportedOptions().stream().map(SocketOption::name)
                .filter(n -> !n.equals("IP_DONTFRAGMENT") && !n.equals("SO_INCOMING_NAPI_ID")).toList()));
        }

        // bind twice, closed sockets, an IPv6 address
        try (DatagramSocket a = new DatagramSocket(new InetSocketAddress(LOOP, 0))) {
            row("in use", fails(() -> new DatagramSocket(a.getLocalSocketAddress())));
            DatagramSocket c = new DatagramSocket(new InetSocketAddress(LOOP, 0));
            c.close();
            row("closed", c.isClosed() + " " + fails(() -> { c.send(packet("x", a.getLocalSocketAddress())); return null; })
                + " " + fails(() -> { c.receive(new DatagramPacket(new byte[1], 1)); return null; }));
        }

        // closing a socket wakes the thread blocked in its receive
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(LOOP, 0));
             DatagramChannel ch = DatagramChannel.open()) {
            ch.bind(new InetSocketAddress(LOOP, 0));
            ExecutorService pool = Executors.newCachedThreadPool();
            CountDownLatch started = new CountDownLatch(2);
            Future<String> f1 = pool.submit(() -> { started.countDown(); return fails(() -> { s.receive(new DatagramPacket(new byte[1], 1)); return null; }); });
            Future<String> f2 = pool.submit(() -> { started.countDown(); return fails(() -> ch.receive(ByteBuffer.allocate(1))); });
            started.await();
            Thread.sleep(300);
            s.close();
            ch.close();
            row("close wakes", f1.get(5, TimeUnit.SECONDS) + " | " + f2.get(5, TimeUnit.SECONDS));
            pool.shutdown();
        }

        // network interfaces: the loopback one, found every way
        List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
        NetworkInterface lo = all.stream().filter(i -> {
            try { return i.isLoopback() && i.isUp(); } catch (SocketException e) { return false; }
        }).findFirst().orElse(null);
        row("interfaces", (all.size() > 0) + " " + (lo != null) + " " + (NetworkInterface.networkInterfaces().count() == all.size())
            + " " + all.stream().allMatch(i -> i.getIndex() > 0 && i.getName() != null && !i.getName().isEmpty()));
        row("loopback addresses", lo.inetAddresses().filter(a -> a instanceof Inet4Address).map(InetAddress::getHostAddress).toList());
        InterfaceAddress ia = lo.getInterfaceAddresses().stream().filter(a -> a.getAddress() instanceof Inet4Address).findFirst().get();
        row("interface address", ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength() + " " + ia.getBroadcast());
        row("found by", lo.equals(NetworkInterface.getByName(lo.getName())) + " " + lo.equals(NetworkInterface.getByIndex(lo.getIndex()))
            + " " + lo.equals(NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1")))
            + " " + lo.equals(NetworkInterface.getByInetAddress(LOOP)));
        row("loopback", lo.isUp() + " " + lo.isLoopback() + " " + lo.isPointToPoint() + " " + lo.isVirtual()
            + " " + lo.getParent() + " " + (lo.getMTU() > 0) + " " + lo.getHardwareAddress()
            + " " + lo.getDisplayName().equals(lo.getName()) + " " + lo.subInterfaces().count());
        row("not found", NetworkInterface.getByName("no-such-interface0") + " " + NetworkInterface.getByIndex(999999)
            + " " + NetworkInterface.getByInetAddress(InetAddress.getByName("192.0.2.77"))
            + " " + fails(() -> NetworkInterface.getByIndex(-1)));
        row("hardware addresses", all.stream().allMatch(i -> {
            try { byte[] m = i.getHardwareAddress(); return m == null || m.length == 6 || m.length == 8 || m.length == 20; }
            catch (SocketException e) { return false; }
        }));
        row("every address bound", all.stream().flatMap(NetworkInterface::inetAddresses)
            .filter(a -> a instanceof Inet4Address).allMatch(a -> {
                try { return NetworkInterface.getByInetAddress(a) != null; } catch (SocketException e) { return false; }
            }));

        // the multicast options: one-byte TTL and loop, the interface as an address
        try (DatagramChannel m = DatagramChannel.open()) {
            m.bind(new InetSocketAddress(LOOP, 0));
            row("multicast defaults", m.getOption(StandardSocketOptions.IP_MULTICAST_IF)
                + " " + m.getOption(StandardSocketOptions.IP_MULTICAST_TTL)
                + " " + m.getOption(StandardSocketOptions.IP_MULTICAST_LOOP));
            m.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 200);
            m.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, false);
            m.setOption(StandardSocketOptions.IP_MULTICAST_IF, lo);
            row("multicast set", m.getOption(StandardSocketOptions.IP_MULTICAST_TTL)
                + " " + m.getOption(StandardSocketOptions.IP_MULTICAST_LOOP)
                + " " + lo.equals(m.getOption(StandardSocketOptions.IP_MULTICAST_IF))
                + " " + fails(() -> m.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 256)));
        }

        // reachability
        row("reachable", LOOP.isReachable(2000) + " " + InetAddress.getByName("127.0.0.1").isReachable(lo, 0, 2000));
        row("not reachable", InetAddress.getByName("192.0.2.1").isReachable(300));
        row("bad timeout", fails(() -> LOOP.isReachable(-1)));
    }
}
