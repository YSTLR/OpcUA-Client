package com.bosch.njp1.server;

import com.bosch.njp1.config.ApplicationConfig;
import com.bosch.njp1.opcua.NodeAccessMonitor;
import com.bosch.njp1.opcua.OpcUaClientPool;
import com.bosch.njp1.opcua.OpcUaSubscribeClientPool;
import com.bosch.njp1.redis.Redis;
import com.bosch.njp1.util.ApplicationUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


public class ReadServer {

    private final OpcUaClientPool clientPool;

    private final ApplicationConfig config;

    private final NodeAccessMonitor monitor;

    private final Redis redis;

    public ReadServer(OpcUaClientPool clientPool, OpcUaSubscribeClientPool subscribeClientPool, Redis redis, ApplicationConfig config) {
        this.clientPool = clientPool;
        this.config = config;
        this.redis = redis;
        this.monitor = new NodeAccessMonitor(
                config,
                subscribeClientPool,
                redis
        );
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer() {
                        @Override
                        protected void initChannel(Channel channel){
                            channel.pipeline().addLast(new HttpServerCodec());
                            channel.pipeline().addLast(new HttpObjectAggregator(65536));
                            channel.pipeline().addLast(new HttpRequestHandler(clientPool, config, monitor, redis));
                        }
                    });

            ChannelFuture future = bootstrap.bind(config.httpServer.port).sync();
            System.out.println("HTTP Server started on port " + config.httpServer.port);
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final OpcUaClientPool clientPool;
        private final ApplicationConfig config;
        private final NodeAccessMonitor monitor;
        private final Redis redis;

        public HttpRequestHandler(OpcUaClientPool clientPool, ApplicationConfig config, NodeAccessMonitor monitor, Redis redis) {
            this.clientPool = clientPool;
            this.config = config;
            this.monitor = monitor;
            this.redis = redis;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request){
            if (!request.method().equals(HttpMethod.GET)) {
                sendResponse(ctx, "{\"error\": \"Only GET methods are supported\"}", HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String path = decoder.path();
            if (path.equals(config.httpServer.readUrl)) {
                handleReadRequest(ctx, decoder);
            } else if (path.equals(config.httpServer.writeUrl)) {
//                handleWriteRequest(ctx, request);
            }
        }

        private void handleReadRequest(ChannelHandlerContext ctx, QueryStringDecoder decoder) {
            String group = decoder.parameters().getOrDefault("group", null) != null
                    ? decoder.parameters().get("group").get(0)
                    : null;

            if (group == null) {
                sendResponse(ctx, "{\"error\": \"Missing 'key' parameter\"}", HttpResponseStatus.BAD_REQUEST);
                return;
            }
            String key = decoder.parameters().getOrDefault("key", null) != null
                    ? decoder.parameters().get("key").get(0)
                    : null;

            if (key == null) {
                sendResponse(ctx, "{\"error\": \"Missing 'key' parameter\"}", HttpResponseStatus.BAD_REQUEST);
                return;
            }
            try {
                //监视器监控节点访问
                monitor.recordTagAccess(group, key);
                String redisValue = redis.read(group + "." + key);
                if(redisValue!=null){
                    sendResponse(ctx, redisValue);
                    return;
                }

                OpcUaClient client = clientPool.borrowClient(config.opcUa.pool.borrowTimeoutMillis, TimeUnit.MILLISECONDS);
                NodeId nodeId = NodeId.parse(ApplicationUtil.parseNodeParam(String.valueOf(config.opcUa.client.namespace), group, key));

                // 异步读取节点值
                CompletableFuture<DataValue> future = client.readValue(0, org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn.Both, nodeId);

                future.thenAccept(value -> {
                    if (value.getStatusCode().isGood()) {
                        System.out.println(value.getValue());
                        sendResponse(ctx, value.getValue().getValue().toString());
                    } else {
                        sendResponse(ctx, "{\"error\": \"Failed to read node value\", \"status\": \"" + value.getStatusCode() + "\"}",
                                HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    }

                    clientPool.returnClient(client);
                }).exceptionally(ex -> {
                    sendResponse(ctx, "{\"error\": \"Exception occurred: " + ex.getMessage() + "\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    clientPool.returnClient(client);
                    return null;
                });

            } catch (Exception e) {
                sendResponse(ctx, "{\"error\": \"Unable to borrow client: " + e.getMessage() + "\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }
        }

//        private void handleWriteRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
//            // TODO: 实现写入逻辑
//            sendResponse(ctx, "{\"message\": \"Write functionality is not implemented yet\"}", HttpResponseStatus.NOT_IMPLEMENTED);
//        }

        private void sendResponse(ChannelHandlerContext ctx, String content) {
            sendResponse(ctx, content, HttpResponseStatus.OK);
        }

        private void sendResponse(ChannelHandlerContext ctx, String content, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            response.content().writeCharSequence(content, java.nio.charset.StandardCharsets.UTF_8);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

    }
}
