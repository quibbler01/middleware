package com.alibaba.middleware.race.mom;

import java.nio.charset.Charset;
import java.util.Arrays;

import com.google.gson.Gson;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

public class DefaultConsumer implements Consumer {
	private String brokerIp;
	private String topic;
	private String filter;
	private MessageListener listener;
	private String groupId;

	private static Charset charset = Charset.forName("UTF-8");
	private EventLoopGroup workerGroup;
	private ChannelHandlerContext context;

	public DefaultConsumer() {
		brokerIp = System.getProperty("SIP", "localhost");
		workerGroup = new NioEventLoopGroup();
		
		try {
			Bootstrap b = new Bootstrap();
			b.group(workerGroup).channel(NioSocketChannel.class)//.option(ChannelOption.TCP_NODELAY, true)
					.handler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch) throws Exception {
							ChannelPipeline pipeline = ch.pipeline();
							pipeline.addLast("framer",
									new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
							pipeline.addLast("decoder", new StringDecoder());
							pipeline.addLast("encoder", new StringEncoder());
							pipeline.addLast("handler", new DefaultConsumerHandler());
						}
					});

			b.connect(brokerIp, 9999).sync();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void start() {
		Message message = new Message();
		message.setBody("SUB".getBytes(charset));
		message.setTopic(topic);
		if (filter.equals("") == false) {
			message.setProperty(filter.split("=")[0], filter.split("=")[1]);
		}

		context.writeAndFlush(new Gson().toJson(message) + "\r\n"); // consumer 》 broker，订阅消息
	}

	public class DefaultConsumerHandler extends SimpleChannelInboundHandler<String> {
		private Message ack = new Message();

		{
			ack.setBody("ACK".getBytes(charset));
		}

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
			context = ctx;
		}

		@Override
		public void channelRead0(ChannelHandlerContext ctx, String string) throws Exception {
			Message message = new Gson().fromJson(string, Message.class);

			if (Arrays.equals(message.getBody(), "ACK".getBytes(charset))) {
//				System.out.println("订阅成功");
			} else {
				ack.setMsgId(message.getMsgId());
				ctx.writeAndFlush(new Gson().toJson(ack) + "\r\n");

				System.out.println("消费成功 #" + message.getMsgId());
				listener.onMessage(message);
			}
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			cause.printStackTrace();
			ctx.close();
		}
	}

	@Override
	public void subscribe(String topic, String filter, MessageListener listener) {
		this.topic = topic;
		this.filter = filter;
		this.listener = listener;
	}

	@Override
	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	@Override
	public void stop() {
		try {
			context.channel().close();
			workerGroup.shutdownGracefully();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
