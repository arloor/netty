/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.http.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.example.socksproxy.DirectClientHandler;
import io.netty.example.socksproxy.RelayHandler;
import io.netty.example.socksproxy.SocksServerUtils;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse;
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

public class HttpProxyConnectHandler extends SimpleChannelInboundHandler<HttpObject> {
    private static final byte[] CONTENT = { 'H', 'e', 'l', 'l', 'o', ' ', 'W', 'o', 'r', 'l', 'd' };

    private final Bootstrap b = new Bootstrap();


    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpRequest) {
            final HttpRequest req = (HttpRequest) msg;
            System.out.println(req.method()+" "+req.uri());
            //如果是CONNECT
            if(req.method().equals(HttpMethod.CONNECT)){
                //获取Host和port
                String hostAndPortStr = req.headers().get("Host");
                String[] hostPortArray=hostAndPortStr.split(":");
                String host=hostPortArray[0];
                String portStr=hostPortArray.length==2?hostPortArray[1]:"80";
                int port=Integer.parseInt(portStr);
                //连接失败的promise
                Promise<Channel> promise = ctx.executor().newPromise();
                promise.addListener(
                        new FutureListener<Channel>() {
                            @Override
                            public void operationComplete(final Future<Channel> future) throws Exception {
                                final Channel outboundChannel = future.getNow();
                                if (future.isSuccess()) {
                                    ChannelFuture responseFuture = ctx.channel().writeAndFlush(
                                            new DefaultHttpResponse(req.protocolVersion(),OK));

                                    responseFuture.addListener(new ChannelFutureListener() {
                                        @Override
                                        public void operationComplete(ChannelFuture channelFuture) {
                                            ctx.pipeline().remove(ChannelHandler.class);
                                            outboundChannel.pipeline().addLast(new RelayHandler(ctx.channel()));
                                            ctx.pipeline().addLast(new RelayHandler(outboundChannel));
                                        }
                                    });
                                } else {
                                    ctx.channel().writeAndFlush(
                                            new DefaultHttpResponse(req.protocolVersion(),INTERNAL_SERVER_ERROR)
                                    );
                                    SocksServerUtils.closeOnFlush(ctx.channel());
                                }
                            }
                        });

                final Channel inboundChannel = ctx.channel();
                b.group(inboundChannel.eventLoop())
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .handler(new DirectClientHandler(promise));

                b.connect(host, port).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            // Connection established use handler provided results
                        } else {
                            // Close the connection if the connection attempt has failed.
                            //返回500，并关闭连接
                            ctx.channel().writeAndFlush(
                                    new DefaultHttpResponse(req.protocolVersion(),INTERNAL_SERVER_ERROR)
                            );
                            SocksServerUtils.closeOnFlush(ctx.channel());
                        }
                    }
                });
            }
            else{
                req.headers().forEach(System.out::println);

                boolean keepAlive = HttpUtil.isKeepAlive(req);
                FullHttpResponse response = new DefaultFullHttpResponse(req.protocolVersion(), OK,
                        Unpooled.wrappedBuffer(CONTENT));
                response.headers()
                        .set(CONTENT_TYPE, TEXT_PLAIN)
                        .setInt(CONTENT_LENGTH, response.content().readableBytes());

                if (keepAlive) {
                    if (!req.protocolVersion().isKeepAliveDefault()) {
                        response.headers().set(CONNECTION, KEEP_ALIVE);
                    }
                } else {
                    // Tell the client we're going to close the connection.
                    response.headers().set(CONNECTION, CLOSE);
                }

                ChannelFuture f = ctx.write(response);

                if (!keepAlive) {
                    f.addListener(ChannelFutureListener.CLOSE);
                }
            }


        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
