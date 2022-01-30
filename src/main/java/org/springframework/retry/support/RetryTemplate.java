/*
 * Copyright 2006-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.retry.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.retry.ExhaustedRetryException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryException;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryOperations;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.RetryState;
import org.springframework.retry.TerminatedRetryException;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.MapRetryContextCache;
import org.springframework.retry.policy.RetryContextCache;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Template class that simplifies the execution of operations with retry semantics.
 * <p>
 * Retryable operations are encapsulated in implementations of the {@link RetryCallback}
 * interface and are executed using one of the supplied execute methods.
 * <p>
 * By default, an operation is retried if is throws any {@link Exception} or subclass of
 * {@link Exception}. This behaviour can be changed by using the
 * {@link #setRetryPolicy(RetryPolicy)} method.
 * <p>
 * Also by default, each operation is retried for a maximum of three attempts with no back
 * off in between. This behaviour can be configured using the
 * {@link #setRetryPolicy(RetryPolicy)} and {@link #setBackOffPolicy(BackOffPolicy)}
 * properties. The {@link org.springframework.retry.backoff.BackOffPolicy} controls how
 * long the pause is between each individual retry attempt.
 * <p>
 * A new instance can be fluently configured via {@link #builder}, e.g: <pre> {@code
 * RetryTemplate.builder()
 *                 .maxAttempts(10)
 *                 .fixedBackoff(1000)
 *                 .build();
 * }</pre> See {@link RetryTemplateBuilder} for more examples and details.
 * <p>
 * This class is thread-safe and suitable for concurrent access when executing operations
 * and when performing configuration changes. As such, it is possible to change the number
 * of retries on the fly, as well as the {@link BackOffPolicy} used and no in progress
 * retryable operations will be affected.
 *
 * 重试模板
 * @author Rob Harrop
 * @author Dave Syer
 * @author Gary Russell
 * @author Artem Bilan
 * @author Josh Long
 * @author Aleksandr Shamukov
 */
public class RetryTemplate implements RetryOperations {

	/**
	 * Retry context attribute name that indicates the context should be considered global
	 * state (never closed). TODO: convert this to a flag in the RetryState.
	 */
	private static final String GLOBAL_STATE = "state.global";

	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * 回退机制，默认无回退机制
	 */
	private volatile BackOffPolicy backOffPolicy = new NoBackOffPolicy();

	/**
	 * 重试机制，默认是固定重试次数机制
	 */
	private volatile RetryPolicy retryPolicy = new SimpleRetryPolicy(3);

	/**
	 * 监听器集合
	 */
	private volatile RetryListener[] listeners = new RetryListener[0];

	/**
	 * 重试上下文缓存对象
	 */
	private RetryContextCache retryContextCache = new MapRetryContextCache();

	/**
	 * 是否需要在用尽资源时抛出最后一个异常
	 */
	private boolean throwLastExceptionOnExhausted;

	/**
	 * Main entry point to configure RetryTemplate using fluent API. See
	 * {@link RetryTemplateBuilder} for usage examples and details.
	 * @return a new instance of RetryTemplateBuilder with preset default behaviour, that
	 * can be overwritten during manual configuration
	 * @since 1.3
	 */
	public static RetryTemplateBuilder builder() {
		return new RetryTemplateBuilder();
	}

	/**
	 * Creates a new default instance. The properties of default instance are described in
	 * {@link RetryTemplateBuilder} documentation.
	 * @return a new instance of RetryTemplate with default behaviour
	 * @since 1.3
	 */
	public static RetryTemplate defaultInstance() {
		return new RetryTemplateBuilder().build();
	}

	/**
	 * @param throwLastExceptionOnExhausted the throwLastExceptionOnExhausted to set
	 */
	public void setThrowLastExceptionOnExhausted(boolean throwLastExceptionOnExhausted) {
		this.throwLastExceptionOnExhausted = throwLastExceptionOnExhausted;
	}

	/**
	 * Public setter for the {@link RetryContextCache}.
	 * @param retryContextCache the {@link RetryContextCache} to set.
	 */
	public void setRetryContextCache(RetryContextCache retryContextCache) {
		this.retryContextCache = retryContextCache;
	}

	/**
	 * Setter for listeners. The listeners are executed before and after a retry block
	 * (i.e. before and after all the attempts), and on an error (every attempt).
	 * @param listeners the {@link RetryListener}s
	 * @see RetryListener
	 */
	public void setListeners(RetryListener[] listeners) {
		this.listeners = Arrays.asList(listeners).toArray(new RetryListener[listeners.length]);
	}

	/**
	 * Register an additional listener at the end of the list.
	 * @param listener the {@link RetryListener}
	 * @see #setListeners(RetryListener[])
	 */
	public void registerListener(RetryListener listener) {
		registerListener(listener, this.listeners.length);
	}

	/**
	 * Register an additional listener at the specified index.
	 * @param listener the {@link RetryListener}
	 * @param index the position in the list.
	 * @since 1.3
	 * @see #setListeners(RetryListener[])
	 */
	public void registerListener(RetryListener listener, int index) {
		List<RetryListener> list = new ArrayList<RetryListener>(Arrays.asList(this.listeners));
		if (index >= list.size()) {
			list.add(listener);
		}
		else {
			list.add(index, listener);
		}
		this.listeners = list.toArray(new RetryListener[list.size()]);
	}

	/**
	 * Return true if at least one listener is registered.
	 * @return true if listeners present.
	 * @since 1.3
	 */
	public boolean hasListeners() {
		return this.listeners.length > 0;
	}

	/**
	 * Setter for {@link BackOffPolicy}.
	 * @param backOffPolicy the {@link BackOffPolicy}
	 */
	public void setBackOffPolicy(BackOffPolicy backOffPolicy) {
		this.backOffPolicy = backOffPolicy;
	}

	/**
	 * Setter for {@link RetryPolicy}.
	 * @param retryPolicy the {@link RetryPolicy}
	 */
	public void setRetryPolicy(RetryPolicy retryPolicy) {
		this.retryPolicy = retryPolicy;
	}

	/**
	 * Keep executing the callback until it either succeeds or the policy dictates that we
	 * stop, in which case the most recent exception thrown by the callback will be
	 * rethrown.
	 *
	 * @see RetryOperations#execute(RetryCallback)
	 * @param retryCallback the {@link RetryCallback}
	 * @throws TerminatedRetryException if the retry has been manually terminated by a
	 * listener.
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback) throws E {
		return doExecute(retryCallback, null, null);
	}

	/**
	 * Keep executing the callback until it either succeeds or the policy dictates that we
	 * stop, in which case the recovery callback will be executed.
	 *
	 * @see RetryOperations#execute(RetryCallback, RecoveryCallback)
	 * @param retryCallback the {@link RetryCallback}
	 * @param recoveryCallback the {@link RecoveryCallback}
	 * @throws TerminatedRetryException if the retry has been manually terminated by a
	 * listener.
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback,
			RecoveryCallback<T> recoveryCallback) throws E {
		return doExecute(retryCallback, recoveryCallback, null);
	}

	/**
	 * Execute the callback once if the policy dictates that we can, re-throwing any
	 * exception encountered so that clients can re-present the same task later.
	 *
	 * @see RetryOperations#execute(RetryCallback, RetryState)
	 * @param retryCallback the {@link RetryCallback}
	 * @param retryState the {@link RetryState}
	 * @throws ExhaustedRetryException if the retry has been exhausted.
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback, RetryState retryState)
			throws E, ExhaustedRetryException {
		return doExecute(retryCallback, null, retryState);
	}

	/**
	 * Execute the callback once if the policy dictates that we can, re-throwing any
	 * exception encountered so that clients can re-present the same task later.
	 *
	 * @see RetryOperations#execute(RetryCallback, RetryState)
	 * @param retryCallback the {@link RetryCallback}
	 * @param recoveryCallback the {@link RecoveryCallback}
	 * @param retryState the {@link RetryState}
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback,
			RecoveryCallback<T> recoveryCallback, RetryState retryState) throws E, ExhaustedRetryException {
		return doExecute(retryCallback, recoveryCallback, retryState);
	}

	/**
	 * Execute the callback once if the policy dictates that we can, otherwise execute the
	 * recovery callback.
	 * @param recoveryCallback the {@link RecoveryCallback}
	 * @param retryCallback the {@link RetryCallback}
	 * @param state the {@link RetryState}
	 * @param <T> the type of the return value
	 * @param <E> the exception type to throw
	 * @see RetryOperations#execute(RetryCallback, RecoveryCallback, RetryState)
	 * @throws ExhaustedRetryException if the retry has been exhausted.
	 * @throws E an exception if the retry operation fails
	 * @return T the retried value
	 */
	protected <T, E extends Throwable> T doExecute(RetryCallback<T, E> retryCallback,
			RecoveryCallback<T> recoveryCallback, RetryState state) throws E, ExhaustedRetryException {

		// 获取重试机制接口
		RetryPolicy retryPolicy = this.retryPolicy;
		// 获取重试的回退机制
		BackOffPolicy backOffPolicy = this.backOffPolicy;

		// Allow the retry policy to initialise itself...
		// 获取重试上下文
		RetryContext context = open(retryPolicy, state);
		if (this.logger.isTraceEnabled()) {
			this.logger.trace("RetryContext retrieved: " + context);
		}

		// Make sure the context is available globally for clients who need
		// it...
		// 将当前的重试上下文放入到线程变量中
		RetrySynchronizationManager.register(context);

		// 最后的异常对象
		Throwable lastException = null;

		// 是否执行完成try代码块的标记
		boolean exhausted = false;
		try {

			// Give clients a chance to enhance the context...
			// 执行监听器的open方法
			boolean running = doOpenInterceptors(retryCallback, context);

			// 监听器集合处理结果返回false则需要抛出异常
			if (!running) {
				throw new TerminatedRetryException("Retry terminated abnormally by interceptor before first attempt");
			}

			// Get or Start the backoff context...
			// 获取回退上下文
			BackOffContext backOffContext = null;
			// 尝试从重试上下文中获取回退上下文
			Object resource = context.getAttribute("backOffContext");

			// 在重试上下文中的回退上下文类型是BackOffContext做类型转换后赋值
			if (resource instanceof BackOffContext) {
				backOffContext = (BackOffContext) resource;
			}

			// 回退上下文为空
			if (backOffContext == null) {
				// 回退机制中开启回退上下文
				backOffContext = backOffPolicy.start(context);
				// 开启后的回退上下文不为空则放入到重试上下文的属性中
				if (backOffContext != null) {
					context.setAttribute("backOffContext", backOffContext);
				}
			}

			/*
			 * We allow the whole loop to be skipped if the policy or context already
			 * forbid the first try. This is used in the case of external retry to allow a
			 * recovery in handleRetryExhausted without the callback processing (which
			 * would throw an exception).
			// 循环条件: 允许重试,资源未耗尽
			 */
			while (canRetry(retryPolicy, context) && !context.isExhaustedOnly()) {

				try {
					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Retry: count=" + context.getRetryCount());
					}
					// Reset the last exception, so if we are successful
					// the close interceptors will not think we failed...
					lastException = null;
					// 调用RetryCallback的doWithRetry方法
					return retryCallback.doWithRetry(context);
				}
				catch (Throwable e) {

					// 将当前异常设置给最后的异常对象
					lastException = e;

					try {
						// 注册异常
						registerThrowable(retryPolicy, state, context, e);
					}
					catch (Exception ex) {
						throw new TerminatedRetryException("Could not register throwable", ex);
					}
					finally {
						// 执行监听器的onError方法
						doOnErrorInterceptors(retryCallback, context, e);
					}

					// 允许重试,资源未耗尽
					if (canRetry(retryPolicy, context) && !context.isExhaustedOnly()) {
						try {
							// 执行回退机制的backOff方法
							backOffPolicy.backOff(backOffContext);
						}
						catch (BackOffInterruptedException ex) {
							// 最后的异常数据覆盖为BackOffInterruptedException异常
							lastException = e;
							// back off was prevented by another thread - fail the retry
							if (this.logger.isDebugEnabled()) {
								this.logger.debug("Abort retry because interrupted: count=" + context.getRetryCount());
							}
							throw ex;
						}
					}

					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Checking for rethrow: count=" + context.getRetryCount());
					}

					// 是否应该抛出异常
					if (shouldRethrow(retryPolicy, context, state)) {
						if (this.logger.isDebugEnabled()) {
							this.logger.debug("Rethrow in retry for policy: count=" + context.getRetryCount());
						}
						// 包装异常后抛出
						throw RetryTemplate.<E>wrapIfNecessary(e);
					}

				}

				/*
				 * A stateful attempt that can retry may rethrow the exception before now,
				 * but if we get this far in a stateful retry there's a reason for it,
				 * like a circuit breaker or a rollback classifier.
				 */
				// 重试状态为空并且存在GLOBAL_STATE属性跳出本次循环
				if (state != null && context.hasAttribute(GLOBAL_STATE)) {
					break;
				}
			}

			// 重试状态为空记录日志
			if (state == null && this.logger.isDebugEnabled()) {
				this.logger.debug("Retry failed last attempt: count=" + context.getRetryCount());
			}

			// 是否执行完成try代码块的标记设置为true
			exhausted = true;
			// 处理重试用尽的情况
			return handleRetryExhausted(recoveryCallback, context, state);

		}
		// 异常抛出
		catch (Throwable e) {
			throw RetryTemplate.<E>wrapIfNecessary(e);
		}
		// 每次都需要执行的代码
		finally {
			// 调用关闭方法
			close(retryPolicy, context, state, lastException == null || exhausted);
			// 监听器执行close方法
			doCloseInterceptors(retryCallback, context, lastException);
			// 清理重试上下文
			RetrySynchronizationManager.clear();
		}

	}

	/**
	 * Decide whether to proceed with the ongoing retry attempt. This method is called
	 * before the {@link RetryCallback} is executed, but after the backoff and open
	 * interceptors.
	 * @param retryPolicy the policy to apply
	 * @param context the current retry context
	 * @return true if we can continue with the attempt
	 */
	protected boolean canRetry(RetryPolicy retryPolicy, RetryContext context) {
		return retryPolicy.canRetry(context);
	}

	/**
	 * Clean up the cache if necessary and close the context provided (if the flag
	 * indicates that processing was successful).
	 * @param retryPolicy the {@link RetryPolicy}
	 * @param context the {@link RetryContext}
	 * @param state the {@link RetryState}
	 * @param succeeded whether the close succeeded , 是否成功关闭
	 */
	protected void close(RetryPolicy retryPolicy, RetryContext context, RetryState state, boolean succeeded) {
		if (state != null) {
			if (succeeded) {
				if (!context.hasAttribute(GLOBAL_STATE)) {
					this.retryContextCache.remove(state.getKey());
				}
				retryPolicy.close(context);
				context.setAttribute(RetryContext.CLOSED, true);
			}
		}
		else {
			retryPolicy.close(context);
			context.setAttribute(RetryContext.CLOSED, true);
		}
	}

	protected void registerThrowable(RetryPolicy retryPolicy, RetryState state, RetryContext context, Throwable e) {
		retryPolicy.registerThrowable(context, e);
		registerContext(context, state);
	}

	private void registerContext(RetryContext context, RetryState state) {
		if (state != null) {
			Object key = state.getKey();
			if (key != null) {
				if (context.getRetryCount() > 1 && !this.retryContextCache.containsKey(key)) {
					throw new RetryException("Inconsistent state for failed item key: cache key has changed. "
							+ "Consider whether equals() or hashCode() for the key might be inconsistent, "
							+ "or if you need to supply a better key");
				}
				this.retryContextCache.put(key, context);
			}
		}
	}

	/**
	 * Delegate to the {@link RetryPolicy} having checked in the cache for an existing
	 * value if the state is not null.
	 * @param state a {@link RetryState}
	 * @param retryPolicy a {@link RetryPolicy} to delegate the context creation
	 * @return a retry context, either a new one or the one used last time the same state
	 * was encountered
	 */
	protected RetryContext open(RetryPolicy retryPolicy, RetryState state) {

		// 如果重试状态为空
		if (state == null) {
			// 通过RetryPolicy接口开启上下文
			return doOpenInternal(retryPolicy);
		}

		// 将重试状态中的key获取
		Object key = state.getKey();

		// 判断是否需要强制刷新
		if (state.isForceRefresh()) {
			// 通过RetryPolicy接口开启上下文
			return doOpenInternal(retryPolicy, state);
		}

		// If there is no cache hit we can avoid the possible expense of the
		// cache re-hydration.
		// key在缓存中不存在
		if (!this.retryContextCache.containsKey(key)) {
			// The cache is only used if there is a failure.
			// 通过RetryPolicy接口开启上下文
			return doOpenInternal(retryPolicy, state);
		}

		// 从缓存中获取重试上下文
		RetryContext context = this.retryContextCache.get(key);
		// 重试上下文为空
		if (context == null) {
			// key在缓存中存在数据抛出异常
			if (this.retryContextCache.containsKey(key)) {
				throw new RetryException("Inconsistent state for failed item: no history found. "
						+ "Consider whether equals() or hashCode() for the item might be inconsistent, "
						+ "or if you need to supply a better ItemKeyGenerator");
			}
			// The cache could have been expired in between calls to
			// containsKey(), so we have to live with this:
			// 通过RetryPolicy接口开启上下文
			return doOpenInternal(retryPolicy, state);
		}

		// Start with a clean slate for state that others may be inspecting
		// 移除上下文属性
		context.removeAttribute(RetryContext.CLOSED);
		context.removeAttribute(RetryContext.EXHAUSTED);
		context.removeAttribute(RetryContext.RECOVERED);
		return context;

	}

	private RetryContext doOpenInternal(RetryPolicy retryPolicy, RetryState state) {
		// 通过RetryPolicy接口提供的open方法开启重试上下文
		RetryContext context = retryPolicy.open(RetrySynchronizationManager.getContext());
		// 如果状态不为空则设置状态属性
		if (state != null) {
			context.setAttribute(RetryContext.STATE_KEY, state.getKey());
		}
		// 如果存在全局状态属性则注册缓存上下文
		if (context.hasAttribute(GLOBAL_STATE)) {
			registerContext(context, state);
		}
		return context;
	}

	private RetryContext doOpenInternal(RetryPolicy retryPolicy) {
		return doOpenInternal(retryPolicy, null);
	}

	/**
	 * Actions to take after final attempt has failed. If there is state clean up the
	 * cache. If there is a recovery callback, execute that and return its result.
	 * Otherwise throw an exception.
	 * @param recoveryCallback the callback for recovery (might be null)
	 * @param context the current retry context
	 * @param state the {@link RetryState}
	 * @param <T> the type to classify
	 * @throws Exception if the callback does, and if there is no callback and the state
	 * is null then the last exception from the context
	 * @throws ExhaustedRetryException if the state is not null and there is no recovery
	 * callback
	 * @return T the payload to return
	 * @throws Throwable if there is an error
	 */
	protected <T> T handleRetryExhausted(RecoveryCallback<T> recoveryCallback, RetryContext context, RetryState state)
			throws Throwable {
		// 设置EXHAUSTED属性为真
		context.setAttribute(RetryContext.EXHAUSTED, true);
		// 如果重试状态不为空并且不存在GLOBAL_STATE属性
		if (state != null && !context.hasAttribute(GLOBAL_STATE)) {
			// 重试上下文换成中移除重试状态对应的数据
			this.retryContextCache.remove(state.getKey());
		}
		// 参数recoveryCallback不为空的情况下执行recover方法设置属性RECOVERED为真
		if (recoveryCallback != null) {
			T recovered = recoveryCallback.recover(context);
			context.setAttribute(RetryContext.RECOVERED, true);
			return recovered;
		}
		// 重试状态不为空
		if (state != null) {
			this.logger.debug("Retry exhausted after last attempt with no recovery path.");
			// 抛出异常
			rethrow(context, "Retry exhausted after last attempt with no recovery path");
		}
		// 包装异常抛出
		throw wrapIfNecessary(context.getLastThrowable());
	}

	protected <E extends Throwable> void rethrow(RetryContext context, String message) throws E {
		if (this.throwLastExceptionOnExhausted) {
			@SuppressWarnings("unchecked")
			E rethrow = (E) context.getLastThrowable();
			throw rethrow;
		}
		else {
			throw new ExhaustedRetryException(message, context.getLastThrowable());
		}
	}

	/**
	 * Extension point for subclasses to decide on behaviour after catching an exception
	 * in a {@link RetryCallback}. Normal stateless behaviour is not to rethrow, and if
	 * there is state we rethrow.
	 * @param retryPolicy the retry policy
	 * @param context the current context
	 * @param state the current retryState
	 * @return true if the state is not null but subclasses might choose otherwise
	 */
	protected boolean shouldRethrow(RetryPolicy retryPolicy, RetryContext context, RetryState state) {
		return state != null && state.rollbackFor(context.getLastThrowable());
	}

	private <T, E extends Throwable> boolean doOpenInterceptors(RetryCallback<T, E> callback, RetryContext context) {

		boolean result = true;

		for (RetryListener listener : this.listeners) {
			result = result && listener.open(context, callback);
		}

		return result;

	}

	private <T, E extends Throwable> void doCloseInterceptors(RetryCallback<T, E> callback, RetryContext context,
			Throwable lastException) {
		for (int i = this.listeners.length; i-- > 0;) {
			this.listeners[i].close(context, callback, lastException);
		}
	}

	private <T, E extends Throwable> void doOnErrorInterceptors(RetryCallback<T, E> callback, RetryContext context,
			Throwable throwable) {
		for (int i = this.listeners.length; i-- > 0;) {
			this.listeners[i].onError(context, callback, throwable);
		}
	}

	/**
	 * Re-throws the original throwable if it is an Exception, and wraps non-exceptions
	 * into {@link RetryException}.
	 */
	private static <E extends Throwable> E wrapIfNecessary(Throwable throwable) throws RetryException {
		if (throwable instanceof Error) {
			throw (Error) throwable;
		}
		else if (throwable instanceof Exception) {
			@SuppressWarnings("unchecked")
			E rethrow = (E) throwable;
			return rethrow;
		}
		else {
			throw new RetryException("Exception in retry", throwable);
		}
	}

}
