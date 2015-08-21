package com.alibaba.middleware.race.rpc.api.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.alibaba.middleware.race.rpc.api.*;
import com.alibaba.middleware.race.rpc.context.RpcContext;

public class RpcProviderImpl extends RpcProvider {
	private Class<?> serviceInterface;
	private Object serviceInstance;
	private String version;
	private int timeout;
	private String serializeType;

	public RpcProviderImpl() {
	}

	/**
	 * init Provider
	 */
	private void init() {
		
	}

	/**
	 * set the interface which this provider want to expose as a service
	 * 
	 * @param serviceInterface
	 */
	public RpcProvider serviceInterface(Class<?> serviceInterface) {
		this.serviceInterface = serviceInterface;
		return this;
	}

	/**
	 * set the version of the service
	 * 
	 * @param version
	 */
	public RpcProvider version(String version) {
		this.version = version;
		return this;
	}

	/**
	 * set the instance which implements the service's interface
	 * 
	 * @param serviceInstance
	 */
	public RpcProvider impl(Object serviceInstance) {
		this.serviceInstance = serviceInstance;
		return this;
	}

	/**
	 * set the timeout of the service
	 * 
	 * @param timeout
	 */
	public RpcProvider timeout(int timeout) {
		this.timeout = timeout;
		return this;
	}

	/**
	 * set serialize type of this service
	 * 
	 * @param serializeType
	 */
	public RpcProvider serializeType(String serializeType) {
		this.serializeType = serializeType;
		return this;
	}

	/**
	 * publish this service if you want to publish your service , you need a
	 * registry server. after all , you cannot write servers' ips in config file
	 * when you have 1 million server. you can use ZooKeeper as your registry
	 * server to make your services found by your consumers.
	 */
	public void publish() {
		try {
			ServerSocket server = new ServerSocket(8888);
			final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
			
			while (true) {
				final Socket s = server.accept();
				executor.submit(new Runnable() {
					@Override
					public void run() {
						try {
							ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
							try {
								String methodName = ois.readUTF();
								Class<?>[] parameterTypes = (Class<?>[]) ois.readObject();
								Object[] arguments = (Object[]) ois.readObject();

								ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
								try {
									Method method = serviceInstance.getClass().getMethod(methodName, parameterTypes);
									if (method.getName() == "getMap") {
										RpcContext.addProp("context", "please pass me!"); // testRpcContext
										RpcContext.addProp("hook key", "this is pass by hook"); // testConsumerHook
									}
									Object result = method.invoke(serviceInstance, arguments);
									oos.writeObject(result);
								} catch (InvocationTargetException e) {
									oos.writeObject(e);
								} finally {
									oos.close();
								}
							} catch (IOException e) {
								e.printStackTrace();
							} finally {
								ois.close();
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
