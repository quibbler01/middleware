package com.alibaba.middleware.race.mom;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

public class DefaultBroker {
	private static Charset charset = Charset.forName("UTF-8");

	private ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
	private Map<Channel, Information> consumers = new HashMap<Channel, Information>();
	private Map<String, Message> messages = new HashMap<String, Message>();
	private Map<Channel, LinkedList<String>> repositories = new HashMap<Channel, LinkedList<String>>();
	private Map<String, Counter> counters = new HashMap<String, Counter>();
	
	private RandomAccessFile randomAccessFile;
	private FileChannel fileChannel;
	
	public DefaultBroker() {
		try {
			String separator = System.getProperty("file.separator").equals("\\") == true ? "\\\\" : "/";
			
			String path = System.getProperty("user.home") + separator + "store";
			File file = new File(path);
			if (file.exists() == false) {
				file.mkdir();
			}
						
			randomAccessFile = new RandomAccessFile(path + separator + "ali_middleware_567.txt", "rw");
			fileChannel = randomAccessFile.getChannel();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private class Information {
		private String topic;
		private LinkedTreeMap<String, String> properties;

		public Information(Message message) {
			this.topic = message.getTopic();

			try {
				Field field = Message.class.getDeclaredField("properties");
				field.setAccessible(true);
				this.properties = (LinkedTreeMap<String, String>) field.get(message);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		public boolean filter(Message message) { // 属性过滤
			Information information = new Information(message);
			if (information.topic.equals(this.topic)) {
				if (this.properties.isEmpty() || information.properties.equals(this.properties)) {
					return true;
				}
			}
			return false;
		}
	}

	private class Counter {
		private int x = 0;
		private int y = 0;

		public void incrementX() {
			this.x++; // ACK数
		}

		public void incrementY() {
			this.y++; // 推送数
		}

		public boolean check() {
			if (this.x == this.y) {
				return true;
			} else {
				return false;
			}
		}
	}

	public void start() {
		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
		final EventExecutorGroup group = new DefaultEventExecutorGroup(30);
		
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch) {
							ChannelPipeline pipeline = ch.pipeline();
							pipeline.addLast("framer",
									new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
							pipeline.addLast("decoder", new StringDecoder());
							pipeline.addLast("encoder", new StringEncoder());
							pipeline.addLast(group, "handler", new DefaultBrokerHandler());
						}
					}).option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.SO_REUSEADDR, true)
					.childOption(ChannelOption.SO_KEEPALIVE, true);

			ChannelFuture f = b.bind(9999).sync();
			f.channel().closeFuture().sync();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
			group.shutdownGracefully();
		}
	}

	private class DefaultBrokerHandler extends SimpleChannelInboundHandler<String> {
		private Message ack = new Message();
		private ByteBuffer byteBuffer = ByteBuffer.allocateDirect(256);
		
		public DefaultBrokerHandler() {
			ack.setBody("ACK".getBytes(charset));
		}
		
		@Override
		public void channelRead0(ChannelHandlerContext ctx, String string) throws Exception {
			Message message = new Gson().fromJson(string, Message.class);

			if (Arrays.equals(message.getBody(), "ACK".getBytes(charset))) { // consumer 》 broker，消费
				String msgId = message.getMsgId();

				repositories.get(ctx.channel()).remove(msgId);
				counters.get(msgId).incrementX();
				if (counters.get(msgId).check()) {
					messages.remove(msgId);
					System.out.println("消息队列 %" + messages.size());
					System.out.println("推送成功 #" + msgId);
				}
			} else if (Arrays.equals(message.getBody(), "SUB".getBytes(charset))) { // consumer 》 broker，订阅
				channels.add(ctx.channel());
				consumers.put(ctx.channel(), new Information(message));
				repositories.put(ctx.channel(), new LinkedList<String>());

				ctx.writeAndFlush(new Gson().toJson(ack) + "\r\n");
			} else { // producer 》 broker，生产；broker 》 consumer，推送
				String msgId = message.getMsgId();

//				System.out.println("有新消息 @" + msgId);

				messages.put(msgId, message);
				counters.put(msgId, new Counter());
				for (Channel channel : channels) {
					if (consumers.get(channel).filter(message)) {
						repositories.get(channel).add(msgId);
						counters.get(msgId).incrementY();
						channel.writeAndFlush(string + "\r\n");
					}
				}
				
				byteBuffer.clear();
				byteBuffer.put((string + "\r\n").getBytes(charset));
				byteBuffer.flip();
				while (byteBuffer.hasRemaining()) {
					fileChannel.write(byteBuffer);
				}
				fileChannel.force(true);
				
				ack.setMsgId(msgId);
				ctx.writeAndFlush(new Gson().toJson(ack) + "\r\n");
			}
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
	        cause.printStackTrace();
	        ctx.close();
			try {
				fileChannel.close();
				randomAccessFile.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	public static void main(String[] args) {
		try {
			new DefaultBroker().start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
