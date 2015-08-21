package com.alibaba.middleware.race.mom;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
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

public class DefaultProducer implements Producer {
	private String brokerIp;
	private String topic;
	private String groupId;

	private static Charset charset = Charset.forName("UTF-8");
	private static AtomicLong messageCount = new AtomicLong();
	private EventLoopGroup workerGroup;
	private ChannelHandlerContext context;
	private ConcurrentHashMap<String, BlockingQueue<MessageStatus>> messages = new ConcurrentHashMap<String, BlockingQueue<MessageStatus>>();

	private enum MessageStatus {
		SUCCESS, FAIL
	}

	public DefaultProducer() {
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
							pipeline.addLast("handler", new DefaultProducerHandler());
						}
					});

			b.connect(brokerIp, 9999).sync();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void start() {

	}

	public class DefaultProducerHandler extends SimpleChannelInboundHandler<String> {

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
			context = ctx;
		}

		@Override
		public void channelRead0(ChannelHandlerContext ctx, String string) throws Exception {
			Message message = new Gson().fromJson(string, Message.class);

			if (Arrays.equals(message.getBody(), "ACK".getBytes(charset))) {
//				System.out.println("生产成功 @" + messageCount.get());
				messages.get(message.getMsgId()).add(MessageStatus.SUCCESS);
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			cause.printStackTrace();
			ctx.close();
		}
	}

	@Override
	public void setTopic(String topic) {
		this.topic = topic;
	}

	@Override
	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	@Override
	public SendResult sendMessage(Message message) {
		String msgId = Long.toString(messageCount.incrementAndGet());
		SendResult sendResult = new SendResult();
		
		message.setTopic(topic);
		message.setMsgId(msgId);
		message.setBornTime(System.currentTimeMillis());

		messages.putIfAbsent(msgId, new LinkedBlockingQueue<MessageStatus>(1));
		context.writeAndFlush(new Gson().toJson(message) + "\r\n");

		try {
//			System.out.println("当前阻塞 %" + messages.size());
			MessageStatus status = messages.get(msgId).take();

			if (status == MessageStatus.SUCCESS) {
				sendResult.setStatus(SendStatus.SUCCESS);
				sendResult.setMsgId(msgId);
				return sendResult;
			} else {
				sendResult.setStatus(SendStatus.FAIL);
				sendResult.setMsgId(msgId);
				return sendResult;
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			messages.remove(msgId);
		}
		sendResult.setStatus(SendStatus.FAIL);
		sendResult.setMsgId(msgId);
		return sendResult;
	}

	@Override
	public void asyncSendMessage(Message message, SendCallback callback) {
		try {
			message.setTopic(topic);
			message.setMsgId(Long.toString(messageCount.incrementAndGet()));
			message.setBornTime(System.currentTimeMillis());

			final Message asynMessage = message;
			final SendCallback asynCallback = callback;

			ChannelFuture f = context.writeAndFlush(new Gson().toJson(message) + "\r\n");
			f.addListener(new ChannelFutureListener() {

				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					if (future.isSuccess()) {
						final SendResult sendResult = new SendResult();
						sendResult.setStatus(SendStatus.SUCCESS);
						sendResult.setMsgId(asynMessage.getMsgId());
						asynCallback.onResult(sendResult);
					} else {
						final SendResult sendResult = new SendResult();
						sendResult.setStatus(SendStatus.FAIL);
						sendResult.setMsgId(asynMessage.getMsgId());
						asynCallback.onResult(sendResult);

						Throwable cause = future.cause();
						cause.printStackTrace();
					}
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
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
