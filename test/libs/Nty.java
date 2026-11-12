import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
public class Nty {
  public static void main(String[] a) throws Exception {
    EventLoopGroup boss = new NioEventLoopGroup(1), work = new NioEventLoopGroup(2);
    try {
      Channel server = new ServerBootstrap().group(boss, work).channel(NioServerSocketChannel.class)
        .childHandler(new ChannelInitializer<SocketChannel>() {
          protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(new LineBasedFrameDecoder(1024), new StringDecoder(StandardCharsets.UTF_8), new StringEncoder(StandardCharsets.UTF_8),
              new SimpleChannelInboundHandler<String>() {
                protected void channelRead0(ChannelHandlerContext ctx, String msg) { ctx.writeAndFlush(msg.toUpperCase() + " (" + msg.length() + ")\n"); }
              });
          }
        }).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
      int port = ((InetSocketAddress) server.localAddress()).getPort();
      BlockingQueue<String> replies = new LinkedBlockingQueue<>();
      Channel client = new Bootstrap().group(work).channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<SocketChannel>() {
          protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(new LineBasedFrameDecoder(1024), new StringDecoder(StandardCharsets.UTF_8), new StringEncoder(StandardCharsets.UTF_8),
              new SimpleChannelInboundHandler<String>() { protected void channelRead0(ChannelHandlerContext ctx, String msg) { replies.add(msg); } });
          }
        }).connect("127.0.0.1", port).sync().channel();
      for (String s : new String[] {"hello", "netty über champaca", "x".repeat(500)}) client.writeAndFlush(s + "\n");
      for (int i = 0; i < 3; i++) { String r = replies.poll(10, TimeUnit.SECONDS); System.out.println(r == null ? "timeout" : r.length() > 60 ? r.substring(0, 20) + "... " + r.length() : r); }
      ByteBuf b = PooledByteBufAllocator.DEFAULT.directBuffer(16);
      b.writeInt(0xCAFEBABE).writeLong(-2L).writeCharSequence("hé", StandardCharsets.UTF_8);
      System.out.println(ByteBufUtil.hexDump(b) + " " + b.readableBytes() + " " + b.isDirect() + " " + b.refCnt());
      b.release();
      CompositeByteBuf c = Unpooled.compositeBuffer(); c.addComponents(true, Unpooled.wrappedBuffer(new byte[] {1, 2}), Unpooled.copiedBuffer("ab", StandardCharsets.US_ASCII));
      System.out.println(ByteBufUtil.hexDump(c) + " " + c.numComponents());
      client.close().sync(); server.close().sync();
    } finally { boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); work.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
    System.out.println("done");
  }
}
