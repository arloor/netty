package io.netty.example.http.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;

public class WriteToOutboundChannelHandler extends ChannelInboundHandlerAdapter {
    private Channel outboundChannel;

    public WriteToOutboundChannelHandler(Channel outboundChannel) {
        this.outboundChannel = outboundChannel;

    }


    public void setOutboundChannel(Channel outboundChannel) {
        this.outboundChannel = outboundChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println(msg.getClass());
        FullHttpRequest raw=(FullHttpRequest)msg;
        FullHttpRequest request=new DefaultFullHttpRequest(raw.protocolVersion(),raw.method(),raw.uri(),raw.content());
        request.headers().add(raw.headers());
        if (outboundChannel.isActive() && msg instanceof HttpObject) {
            outboundChannel.writeAndFlush(request);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }
}
