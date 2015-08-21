package com.alibaba.middleware.race.rpc.api.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import com.alibaba.middleware.race.rpc.aop.ConsumerHook;
import com.alibaba.middleware.race.rpc.api.*;
import com.alibaba.middleware.race.rpc.async.ResponseCallbackListener;
import com.alibaba.middleware.race.rpc.async.ResponseFuture;
import com.alibaba.middleware.race.rpc.demo.service.RaceException;
import com.alibaba.middleware.race.rpc.model.RpcResponse;

public class RpcConsumerImpl extends RpcConsumer implements InvocationHandler {
	private String SIP;
	private Class<?> interfaceClazz;
	private String version;
	private int timeout;
	private ConsumerHook hook;
	private Map<String, Integer> asynCallMethods; // 0=同步;1=FutureCall;2=Callback
	private Map<String, ResponseCallbackListener> callbackListeners;

	public RpcConsumerImpl() {
		init();
	}

	/**
	 * init a rpc consumer
	 */
	private void init() {
		SIP = System.getProperty("SIP");
		asynCallMethods = new HashMap<String, Integer>();
		callbackListeners = new HashMap<String, ResponseCallbackListener>();
	}

	/**
	 * set the interface which this consumer want to use actually,it will call a
	 * remote service to get the result of this interface's methods
	 *
	 * @param interfaceClass
	 * @return
	 */
	public RpcConsumer interfaceClass(Class<?> interfaceClass) {
		this.interfaceClazz = interfaceClass;
		for (Method method : interfaceClazz.getMethods()) {
			asynCallMethods.put(method.getName(), 0);
			callbackListeners.put(method.getName(), null);
		}

		return this;
	}

	/**
	 * set the version of the service
	 *
	 * @param version
	 * @return
	 */
	public RpcConsumer version(String version) {
		this.version = version;
		return this;
	}

	/**
	 * set the timeout of the service consumer's time will take precedence of
	 * the provider's timeout
	 *
	 * @param clientTimeout
	 * @return
	 */
	public RpcConsumer clientTimeout(int clientTimeout) {
		this.timeout = clientTimeout;
		return this;
	}

	/**
	 * register a consumer hook to this service
	 * 
	 * @param hook
	 * @return
	 */
	public RpcConsumer hook(ConsumerHook hook) {
		this.hook = hook;
		return this;
	}

	/**
	 * return an Object which can cast to the interface class
	 *
	 * @return
	 */
	public Object instance() {
		// TODO return an Proxy
		return Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] { this.interfaceClazz }, this);
	}

	/**
	 * mark a async method,default future call
	 *
	 * @param methodName
	 */
	public void asynCall(String methodName) {
		asynCall(methodName, null);
	}

	/**
	 * mark a async method with a callback listener
	 *
	 * @param methodName
	 * @param callbackListener
	 */
	public <T extends ResponseCallbackListener> void asynCall(String methodName, T callbackListener) {
		if (callbackListener == null) {
			asynCallMethods.put(methodName, 1);
		} else {
			asynCallMethods.put(methodName, 2);
			callbackListeners.put(methodName, callbackListener);
		}
	}

	public void cancelAsyn(String methodName) {
		asynCallMethods.put(methodName, 0);
		callbackListeners.put(methodName, null);
	}

	public class AsynFutureCallThread implements Callable<Object> {
		private Method method;
		private Object[] args;

		public AsynFutureCallThread(Method method, Object[] args) {
			this.method = method;
			this.args = args;
		}

		public Object call() {
			try {
				Socket socket = new Socket(SIP, 8888);
				ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
				try {
					oos.writeUTF(method.getName());
					oos.writeObject(method.getParameterTypes());
					oos.writeObject(args);

					ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
					try {
						Object result = ois.readObject();
						if (result instanceof InvocationTargetException) {
							throw new RaceException("RaceException");
						}

						RpcResponse rpcResponse = new RpcResponse();
						Class<RpcResponse> rpcResponseClazz = RpcResponse.class;
						Field appResponseField = rpcResponseClazz.getDeclaredField("appResponse");
						appResponseField.setAccessible(true);
						appResponseField.set(rpcResponse, result); // 设置属性

						return rpcResponse;
					} finally {
						ois.close();
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					oos.close();
					socket.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}
	}

	public class AsynCallbackThread implements Runnable {
		private Method method;
		private Object[] args;

		public AsynCallbackThread(Method method, Object[] args) {
			this.method = method;
			this.args = args;
		}

		public void run() {
			try {
				Socket socket = new Socket(SIP, 8888);
				ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
				try {
					oos.writeUTF(method.getName());
					oos.writeObject(method.getParameterTypes());
					oos.writeObject(args);

					ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
					try {
						Object result = ois.readObject();
						if (result instanceof InvocationTargetException) {
							throw new RaceException("RaceException");
						}
						callbackListeners.get(method.getName()).onResponse(result); // 回调
					} finally {
						ois.close();
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					oos.close();
					socket.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Processes a method invocation on a proxy instance and returns the result.
	 * This method will be invoked on an invocation handler when a method is
	 * invoked on a proxy instance that it is associated with.
	 *
	 * @param proxy
	 *            the proxy instance that the method was invoked on
	 * @param method
	 *            the {@code Method} instance corresponding to the interface
	 *            method invoked on the proxy instance. The declaring class of
	 *            the {@code Method} object will be the interface that the
	 *            method was declared in, which may be a superinterface of the
	 *            proxy interface that the proxy class inherits the method
	 *            through.
	 * @param args
	 *            an array of objects containing the values of the arguments
	 *            passed in the method invocation on the proxy instance, or
	 *            {@code null} if interface method takes no arguments. Arguments
	 *            of primitive types are wrapped in instances of the appropriate
	 *            primitive wrapper class, such as {@code java.lang.Integer} or
	 *            {@code java.lang.Boolean}.
	 * @return the value to return from the method invocation on the proxy
	 *         instance. If the declared return type of the interface method is
	 *         a primitive type, then the value returned by this method must be
	 *         an instance of the corresponding primitive wrapper class;
	 *         otherwise, it must be a type assignable to the declared return
	 *         type. If the value returned by this method is {@code null} and
	 *         the interface method's return type is primitive, then a
	 *         {@code NullPointerException} will be thrown by the method
	 *         invocation on the proxy instance. If the value returned by this
	 *         method is otherwise not compatible with the interface method's
	 *         declared return type as described above, a
	 *         {@code ClassCastException} will be thrown by the method
	 *         invocation on the proxy instance.
	 * @throws Throwable
	 *             the exception to throw from the method invocation on the
	 *             proxy instance. The exception's type must be assignable
	 *             either to any of the exception types declared in the
	 *             {@code throws} clause of the interface method or to the
	 *             unchecked exception types {@code java.lang.RuntimeException}
	 *             or {@code java.lang.Error}. If a checked exception is thrown
	 *             by this method that is not assignable to any of the exception
	 *             types declared in the {@code throws} clause of the interface
	 *             method, then an {@link UndeclaredThrowableException}
	 *             containing the exception that was thrown by this method will
	 *             be thrown by the method invocation on the proxy instance.
	 * @see UndeclaredThrowableException
	 */
	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

		try {
			if (asynCallMethods.get(method.getName()) == 0) { // 同步
				Socket socket = new Socket(SIP, 8888);

				ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
				oos.writeUTF(method.getName());
				oos.writeObject(method.getParameterTypes());
				oos.writeObject(args);

				ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
				try {
					Object result = ois.readObject();
					if (result instanceof InvocationTargetException) {
						throw new RaceException("RaceException");
					}
					return result;
				} catch (IOException e) { // 除RaceException
					e.printStackTrace();
				} finally {
					ois.close();
					oos.close();
					socket.close();
				}
			} else if (asynCallMethods.get(method.getName()) == 1) { // FutureCall
				AsynFutureCallThread thread = new AsynFutureCallThread(method, args);
				FutureTask<Object> task = new FutureTask<Object>(thread);
				ResponseFuture.futureThreadLocal.set(task);
				new Thread(task, "AsynFutureCallThread").start();
			} else if (asynCallMethods.get(method.getName()) == 2) { // Callback
				AsynCallbackThread thread = new AsynCallbackThread(method, args);
				new Thread(thread, "AsynCallbackThread").start();
			}
		} catch (IOException e) { // 除RaceException
			e.printStackTrace();
		}

		return null;
	}
}
