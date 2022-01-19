/*
 * Copyright 2015 the original author or authors.
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

package org.springframework.retry.policy;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.context.RetryContextSupport;

/**
 * 基于熔断的重试机制
 * @author Dave Syer
 *
 */
@SuppressWarnings("serial")
public class CircuitBreakerRetryPolicy implements RetryPolicy {

	public static final String CIRCUIT_OPEN = "circuit.open";

	public static final String CIRCUIT_SHORT_COUNT = "circuit.shortCount";

	private static Log logger = LogFactory.getLog(CircuitBreakerRetryPolicy.class);

	/**
	 * 重试策略
	 */
	private final RetryPolicy delegate;
	/**
	 * 超时时间,总时长
	 */
	private long resetTimeout = 20000;
	/**
	 * 打开超时时间，单次
	 */
	private long openTimeout = 5000;

	public CircuitBreakerRetryPolicy() {
		this(new SimpleRetryPolicy());
	}

	public CircuitBreakerRetryPolicy(RetryPolicy delegate) {
		this.delegate = delegate;
	}

	/**
	 * Timeout for resetting circuit in milliseconds. After the circuit opens it will
	 * re-close after this time has elapsed and the context will be restarted.
	 * @param timeout the timeout to set in milliseconds
	 */
	public void setResetTimeout(long timeout) {
		this.resetTimeout = timeout;
	}

	/**
	 * Timeout for tripping the open circuit. If the delegate policy cannot retry and the
	 * time elapsed since the context was started is less than this window, then the
	 * circuit is opened.
	 * @param timeout the timeout to set in milliseconds
	 */
	public void setOpenTimeout(long timeout) {
		this.openTimeout = timeout;
	}

	@Override
	public boolean canRetry(RetryContext context) {
		CircuitBreakerRetryContext circuit = (CircuitBreakerRetryContext) context;
		if (circuit.isOpen()) {
			// 增加计数器
			circuit.incrementShortCircuitCount();
			return false;
		}
		else {
			// 重置计数器
			circuit.reset();
		}
		return this.delegate.canRetry(circuit.context);
	}

	@Override
	public RetryContext open(RetryContext parent) {
		return new CircuitBreakerRetryContext(parent, this.delegate, this.resetTimeout, this.openTimeout);
	}

	@Override
	public void close(RetryContext context) {
		CircuitBreakerRetryContext circuit = (CircuitBreakerRetryContext) context;
		this.delegate.close(circuit.context);
	}

	@Override
	public void registerThrowable(RetryContext context, Throwable throwable) {
		CircuitBreakerRetryContext circuit = (CircuitBreakerRetryContext) context;
		circuit.registerThrowable(throwable);
		this.delegate.registerThrowable(circuit.context, throwable);
	}

	static class CircuitBreakerRetryContext extends RetryContextSupport {

		/**
		 * 重试策略
		 */
		private final RetryPolicy policy;
		/**
		 * 超时时间
		 */
		private final long timeout;
		/**
		 * 打开超时时间
		 */
		private final long openWindow;
		/**
		 * 重试上下文
		 */
		private volatile RetryContext context;
		/**
		 * 开始时间
		 */
		private volatile long start = System.currentTimeMillis();

		private final AtomicInteger shortCircuitCount = new AtomicInteger();

		public CircuitBreakerRetryContext(RetryContext parent, RetryPolicy policy, long timeout,
				long openWindow) {
			super(parent);
			this.policy = policy;
			this.timeout = timeout;
			this.openWindow = openWindow;
			this.context = createDelegateContext(policy, parent);
			setAttribute("state.global", true);
		}

		public void reset() {
			shortCircuitCount.set(0);
			setAttribute(CIRCUIT_SHORT_COUNT, shortCircuitCount.get());
		}

		public void incrementShortCircuitCount() {
			shortCircuitCount.incrementAndGet();
			setAttribute(CIRCUIT_SHORT_COUNT, shortCircuitCount.get());
		}

		private RetryContext createDelegateContext(RetryPolicy policy, RetryContext parent) {
			RetryContext context = policy.open(parent);
			reset();
			return context;
		}

		/**
		 * 判断是否开启
		 *
		 * @return
		 */
		public boolean isOpen() {
			// 获取当前时间和开始时间的时间差
			long time = System.currentTimeMillis() - this.start;
			// 判断是否可以重试
			boolean retryable = this.policy.canRetry(this.context);
			// 不允许重试
			if (!retryable) {
				// 时间差大于超时时间
				if (time > this.timeout) {
					logger.trace("Closing");
					// 创建上下文
					this.context = createDelegateContext(policy, getParent());
					// 重新标记开始时间
					this.start = System.currentTimeMillis();
					// 重新计算是否可以重试
					retryable = this.policy.canRetry(this.context);
				}
				// 时间差小于窗口打开超时时间
				else if (time < this.openWindow) {
					// 不存在CIRCUIT_OPEN属性并且CIRCUIT_OPEN属性为假则设置CIRCUIT_OPEN属性为真并且重新标记开始时间
					if (!hasAttribute(CIRCUIT_OPEN) || (Boolean) getAttribute(CIRCUIT_OPEN) == false) {
						logger.trace("Opening circuit");
						setAttribute(CIRCUIT_OPEN, true);
						this.start = System.currentTimeMillis();
					}

					return true;
				}
			}
			// 允许重试
			else {
				// 时间差大于打开超时时间
				if (time > this.openWindow) {
					// 重新标记开始时间
					logger.trace("Resetting context");
					this.start = System.currentTimeMillis();
					// 创建上下文
					this.context = createDelegateContext(policy, getParent());
				}
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Open: " + !retryable);
			}
			// 设置CIRCUIT_OPEN属性
			setAttribute(CIRCUIT_OPEN, !retryable);
			// 将retryable变量取反返回
			return !retryable;
		}

		@Override
		public int getRetryCount() {
			return this.context.getRetryCount();
		}

		@Override
		public String toString() {
			return this.context.toString();
		}

	}

}
