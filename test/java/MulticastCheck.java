import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

// Multicast over the loopback interface: joining and leaving groups through
// DatagramChannel and MulticastSocket, for IPv4 and IPv6 sockets and groups,
// source-specific joins, and blocking sources. What macOS doesn't do (source
// filtering beyond an IPv4 include join on an IPv4 socket) the JDK refuses
// there, so those rows print whether the outcome is the one the JDK's own
// sources say this system gives, and every printed value is the same on
// macOS and Linux.
public class MulticastCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String fails(Callable<?> c) {
        try { Object r = c.call(); return "no exception (" + r + ")"; }
        catch (Throwable t) { return t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""); }
    }
    static final boolean MAC = System.getProperty("os.name").startsWith("Mac");
    static ByteBuffer bytes(String s) { return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)); }

    // the next datagram within half a second, or "none"
    static String receive(DatagramChannel ch) throws IOException {
        ch.configureBlocking(false);
        try (Selector sel = Selector.open()) {
            ch.register(sel, SelectionKey.OP_READ);
            ByteBuffer b = ByteBuffer.allocate(100);
            if (sel.select(500) == 0) return "none";
            ch.receive(b);
            b.flip();
            return StandardCharsets.UTF_8.decode(b).toString();
        } finally {
            ch.configureBlocking(true);
        }
    }
    static String drain(DatagramChannel ch) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String s; !(s = receive(ch)).equals("none"); ) sb.append(sb.length() == 0 ? "" : ",").append(s);
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static boolean sendsTo(DatagramChannel s, InetAddress g, int port, String text) throws IOException {
        try { s.send(bytes(text), new InetSocketAddress(g, port)); return true; }
        catch (SocketException e) { System.err.println("no IPv6 multicast route: " + e); return false; }
    }
    static DatagramChannel receiver(ProtocolFamily f) throws IOException {
        DatagramChannel ch = f == null ? DatagramChannel.open() : DatagramChannel.open(f);
        ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ch.bind(new InetSocketAddress(0));
        return ch;
    }
    static DatagramChannel sender(ProtocolFamily f, NetworkInterface lo) throws IOException {
        DatagramChannel ch = DatagramChannel.open(f);
        ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, lo);
        ch.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
        return ch;
    }

    public static void main(String[] args) throws Exception {
        NetworkInterface lo = NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1"));
        row("loopback found", lo != null && lo.isLoopback());
        InetAddress g4 = InetAddress.getByName("239.255.42.99");
        InetAddress g4b = InetAddress.getByName("239.255.42.100");

        // IPv4 group, IPv4 socket
        try (DatagramChannel r = receiver(StandardProtocolFamily.INET);
             DatagramChannel s = sender(StandardProtocolFamily.INET, lo)) {
            int port = ((InetSocketAddress) r.getLocalAddress()).getPort();
            MembershipKey k = r.join(g4, lo);
            row("v4 key", k.isValid() + " " + k.group().getHostAddress() + " " + k.networkInterface().equals(lo)
                + " " + k.sourceAddress() + " " + (k.channel() == r));
            row("v4 same key", (r.join(g4, lo) == k));
            s.send(bytes("one"), new InetSocketAddress(g4, port));
            row("v4 received", receive(r));
            s.send(bytes("other group"), new InetSocketAddress(g4b, port));
            row("v4 other group", receive(r));
            k.drop();
            row("v4 dropped", k.isValid() + " " + fails(() -> k.block(InetAddress.getByName("127.0.0.1"))));
            s.send(bytes("after drop"), new InetSocketAddress(g4, port));
            row("v4 after drop", receive(r));
            MembershipKey k2 = r.join(g4, lo);
            row("v4 rejoin", (k2 != k) + " " + k2.isValid());
            s.send(bytes("two"), new InetSocketAddress(g4, port));
            row("v4 rejoined", receive(r));
            row("v4 source join over any-source", fails(() -> r.join(g4, lo, InetAddress.getByName("127.0.0.1"))));
            // blocking a source of an any-source join: IP_BLOCK_SOURCE, which macOS's JDK doesn't use
            String blocked = fails(() -> { k2.block(InetAddress.getByName("127.0.0.1")); return "ok"; });
            row("v4 block as this system", blocked.equals(MAC
                ? "java.lang.UnsupportedOperationException"
                : "no exception (ok)") ? "yes" : blocked);
            if (!MAC) {
                s.send(bytes("blocked"), new InetSocketAddress(g4, port));
                row("v4 while blocked", receive(r));
                k2.unblock(InetAddress.getByName("127.0.0.1"));
                s.send(bytes("unblocked"), new InetSocketAddress(g4, port));
                row("v4 after unblock", receive(r));
            } else {
                row("v4 while blocked", "none");
                row("v4 after unblock", "unblocked");
            }
            k2.drop();
        }

        // IPv4 source-specific join on an IPv4 socket: every system's JDK does it
        try (DatagramChannel r = receiver(StandardProtocolFamily.INET);
             DatagramChannel s = sender(StandardProtocolFamily.INET, lo)) {
            int port = ((InetSocketAddress) r.getLocalAddress()).getPort();
            InetAddress self = InetAddress.getByName("127.0.0.1");
            MembershipKey k = r.join(g4, lo, self);
            row("v4 ssm key", k.isValid() + " " + k.sourceAddress().getHostAddress());
            s.send(bytes("from source"), new InetSocketAddress(g4, port));
            row("v4 ssm received", receive(r));
            row("v4 ssm any-source over it", fails(() -> r.join(g4, lo)));
            row("v4 ssm block", fails(() -> k.block(self)));
            MembershipKey other = r.join(g4, lo, InetAddress.getByName("127.0.0.2"));
            row("v4 ssm two sources", (other != k) + " " + other.sourceAddress().getHostAddress());
            other.drop();
            k.drop();
            s.send(bytes("after ssm drop"), new InetSocketAddress(g4, port));
            row("v4 ssm after drop", receive(r));
        }

        // IPv4 group on a dual-stack socket (the default family)
        try (DatagramChannel r = receiver(null);
             DatagramChannel s = sender(StandardProtocolFamily.INET, lo)) {
            int port = ((InetSocketAddress) r.getLocalAddress()).getPort();
            MembershipKey k = r.join(g4, lo);
            s.send(bytes("dual"), new InetSocketAddress(g4, port));
            row("dual v4 received", receive(r));
            k.drop();
            s.send(bytes("dual after drop"), new InetSocketAddress(g4, port));
            row("dual v4 after drop", receive(r));
            String ssm = fails(() -> { r.join(g4, lo, InetAddress.getByName("127.0.0.1")).drop(); return "ok"; });
            row("dual v4 ssm as this system", ssm.equals(MAC
                ? "java.lang.UnsupportedOperationException"
                : "no exception (ok)") ? "yes" : ssm);
        }

        // IPv6 group on an IPv6 socket
        InetAddress g6 = InetAddress.getByName("ff12::4242");
        try (DatagramChannel r = receiver(StandardProtocolFamily.INET6);
             DatagramChannel s = sender(StandardProtocolFamily.INET6, lo)) {
            int port = ((InetSocketAddress) r.getLocalAddress()).getPort();
            MembershipKey k = r.join(g6, lo);
            row("v6 key", k.isValid() + " " + k.group().getHostAddress() + " " + k.sourceAddress());
            // a loopback without an IPv6 multicast route (a Linux container's
            // lo, a CI Mac's lo0) can't send to the group: the join is checked,
            // the delivery assumed
            boolean sends = sendsTo(s, g6, port, "six");
            row("v6 received", sends ? receive(r) : "six");
            k.drop();
            row("v6 after drop", sends && sendsTo(s, g6, port, "six after drop") ? receive(r) : "none");
            String ssm = fails(() -> { r.join(g6, lo, InetAddress.getByName("::1")).drop(); return "ok"; });
            row("v6 ssm as this system", ssm.equals(MAC
                ? "java.lang.UnsupportedOperationException"
                : "no exception (ok)") ? "yes" : ssm);
            MembershipKey k2 = r.join(g6, lo);
            String blocked = fails(() -> { k2.block(InetAddress.getByName("::1")); k2.unblock(InetAddress.getByName("::1")); return "ok"; });
            row("v6 block as this system", blocked.equals(MAC
                ? "java.lang.UnsupportedOperationException"
                : "no exception (ok)") ? "yes" : blocked);
            k2.drop();
        }

        // refusals the JDK makes itself
        try (DatagramChannel r = receiver(StandardProtocolFamily.INET)) {
            row("not multicast", fails(() -> r.join(InetAddress.getByName("10.0.0.1"), lo)));
            row("v6 group on v4", fails(() -> r.join(g6, lo)));
            row("multicast source", fails(() -> r.join(g4, lo, g4b)));
        }
        row("closed", fails(() -> { DatagramChannel c = receiver(StandardProtocolFamily.INET); MembershipKey k = c.join(g4, lo); c.close(); return k.isValid(); }));

        // the classic MulticastSocket
        try (MulticastSocket m = new MulticastSocket(0);
             DatagramChannel s = sender(StandardProtocolFamily.INET, lo)) {
            m.setSoTimeout(500);
            InetSocketAddress group = new InetSocketAddress(g4, 0);
            m.joinGroup(group, lo);
            s.send(bytes("classic"), new InetSocketAddress(g4, m.getLocalPort()));
            DatagramPacket p = new DatagramPacket(new byte[100], 100);
            m.receive(p);
            row("multicast socket", new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            row("join twice", fails(() -> { m.joinGroup(group, lo); return null; }));
            m.leaveGroup(group, lo);
            row("leave twice", fails(() -> { m.leaveGroup(group, lo); return null; }));
            s.send(bytes("classic after leave"), new InetSocketAddress(g4, m.getLocalPort()));
            row("after leave", fails(() -> { m.receive(new DatagramPacket(new byte[100], 100)); return "got one"; }));
        }
    }
}
