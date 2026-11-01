package com.yandu.sync.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.sync.ReleaseSyncProtocol;
import com.yandu.sync.ReleaseSyncService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Newline-delimited JSON server for public, one-way published-version notices. */
public class NettyReleaseSyncServer implements SmartLifecycle {

    private static final AttributeKey<AutoCloseable> REGISTRATION =
            AttributeKey.valueOf("yandu.releaseSync.registration");
    private final ObjectMapper json;
    private final ReleaseSyncService releases;
    private final int port;
    private volatile NioEventLoopGroup boss;
    private volatile NioEventLoopGroup workers;
    private volatile Channel serverChannel;

    public NettyReleaseSyncServer(ObjectMapper json, ReleaseSyncService releases, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Netty sync port is invalid");
        this.json = json;
        this.releases = releases;
        this.port = port;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        try {
            serverChannel = new ServerBootstrap().group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new LineBasedFrameDecoder(16_384));
                            channel.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
                            channel.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
                            channel.pipeline().addLast(new IdleStateHandler(0, 25, 0, TimeUnit.SECONDS));
                            channel.pipeline().addLast(new SubscriptionHandler());
                        }
                    }).bind(port).sync().channel();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Interrupted while starting Netty translation sync", interrupted);
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (boss != null) { boss.shutdownGracefully().syncUninterruptibly(); boss = null; }
        if (workers != null) { workers.shutdownGracefully().syncUninterruptibly(); workers = null; }
    }

    @Override public boolean isRunning() { return serverChannel != null && serverChannel.isActive(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }

    private final class SubscriptionHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext context, String line) {
            try {
                var subscription = ReleaseSyncProtocol.subscription(json.readTree(line));
                closeRegistration(context.channel());
                var registration = releases.subscribe(subscription,
                        notice -> sendChanged(context, notice));
                context.channel().attr(REGISTRATION).set(registration);
                releases.latest(subscription).filter(latest ->
                        !latest.checksumSha256().equals(subscription.afterChecksum()))
                        .ifPresent(latest -> context.writeAndFlush(
                                writeChanged(latest) + "\n"));
            } catch (Exception invalid) {
                try {
                    context.writeAndFlush("{\"type\":\"error\",\"message\":\"Invalid subscription request\"}\n");
                } catch (RuntimeException ignored) { context.close(); }
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
            if (event instanceof IdleStateEvent idle && idle.state() == IdleState.WRITER_IDLE) {
                context.writeAndFlush("{\"type\":\"heartbeat\"}\n");
            } else super.userEventTriggered(context, event);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            closeRegistration(context.channel());
            super.channelInactive(context);
        }

        private void sendChanged(ChannelHandlerContext context, ReleaseSyncService.ReleaseNotice notice) {
            if (!context.channel().isActive()) { closeRegistration(context.channel()); return; }
            try {
                context.writeAndFlush(writeChanged(notice) + "\n");
            } catch (RuntimeException failure) {
                closeRegistration(context.channel());
                context.close();
            }
        }

        private String writeChanged(ReleaseSyncService.ReleaseNotice notice) {
            try { return json.writeValueAsString(ReleaseSyncProtocol.changed(notice)); }
            catch (Exception failure) { throw new IllegalStateException("Could not encode release notice", failure); }
        }

        private void closeRegistration(Channel channel) {
            var registration = channel.attr(REGISTRATION).getAndSet(null);
            if (registration != null) {
                try { registration.close(); } catch (Exception ignored) { }
            }
        }
    }
}
