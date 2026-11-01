package com.yandu.consumer.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.consumer.TranslationSyncProperties;
import com.yandu.consumer.TranslationSyncTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** Optional Netty client module; this code is absent unless this artifact is on the classpath. */
public class NettySyncTransport implements TranslationSyncTransport {

    private static final Log log = LogFactory.getLog(NettySyncTransport.class);

    @Override public String mode() { return "netty"; }

    @Override
    public AutoCloseable connect(TranslationSyncProperties properties, ObjectMapper json,
                                 Supplier<String> currentChecksum, Runnable onChange) {
        return new Connection(properties, json, currentChecksum, onChange);
    }

    private static final class Connection implements AutoCloseable {
        private final TranslationSyncProperties properties;
        private final ObjectMapper json;
        private final Supplier<String> currentChecksum;
        private final Runnable onChange;
        private final String host;
        private final int port;
        private final NioEventLoopGroup group = new NioEventLoopGroup(1);
        private final Bootstrap bootstrap = new Bootstrap();
        private volatile Channel channel;
        private volatile boolean running = true;
        private boolean retryPending;

        Connection(TranslationSyncProperties properties, ObjectMapper json,
                   Supplier<String> currentChecksum, Runnable onChange) {
            this.properties = properties;
            this.json = json;
            this.currentChecksum = currentChecksum;
            this.onChange = onChange;
            var hostFromUrl = URI.create(properties.getApiBaseUrl()).getHost();
            this.host = properties.getNettyHost() == null || properties.getNettyHost().isBlank()
                    ? hostFromUrl : properties.getNettyHost();
            this.port = properties.getNettyPort();
            if (host == null || host.isBlank() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("A valid YANDU Netty host and port are required");
            }
            bootstrap.group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socket) {
                            socket.pipeline().addLast(new LineBasedFrameDecoder(16_384));
                            socket.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
                            socket.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
                            socket.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                                @Override public void channelActive(io.netty.channel.ChannelHandlerContext ctx) {
                                    try { ctx.writeAndFlush(json.writeValueAsString(subscription()) + "\n"); }
                                    catch (Exception failure) { ctx.close(); }
                                }
                                @Override protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx,
                                                                      String line) {
                                    try {
                                        var type = json.readTree(line).path("type").asText();
                                        if ("release-changed".equals(type) || "heartbeat".equals(type)) {
                                            java.util.concurrent.CompletableFuture.runAsync(onChange);
                                        }
                                    } catch (Exception invalidMessage) {
                                        log.warn("YANDU Netty sync received an invalid notification");
                                    }
                                }
                                @Override public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) {
                                    scheduleReconnect();
                                }
                                @Override public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx,
                                                                      Throwable cause) {
                                    ctx.close();
                                }
                            });
                        }
                    });
            connect();
        }

        private synchronized void connect() {
            if (!running) return;
            retryPending = false;
            bootstrap.connect(host, port).addListener(future -> {
                if (future.isSuccess()) channel = ((io.netty.channel.ChannelFuture) future).channel();
                else scheduleReconnect();
            });
        }

        private synchronized void scheduleReconnect() {
            if (!running || retryPending) return;
            retryPending = true;
            group.next().schedule(this::connect, 1, TimeUnit.SECONDS);
        }

        private Map<String, Object> subscription() {
            var request = new HashMap<String, Object>();
            request.put("type", "subscribe");
            request.put("projectId", properties.getProjectId());
            request.put("locale", properties.configuredLocales().get(0));
            request.put("format", properties.getFormat());
            request.put("afterChecksum", currentChecksum.get());
            return request;
        }

        @Override
        public void close() {
            running = false;
            if (channel != null) channel.close();
            group.shutdownGracefully(0, Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)
                    .syncUninterruptibly();
        }
    }
}
