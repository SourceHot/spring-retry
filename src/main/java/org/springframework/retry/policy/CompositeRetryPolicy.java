/*
 * Copyright 2006-2007 the original author or authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.context.RetryContextSupport;

/**
 * A {@link RetryPolicy} that composes a list of other policies and delegates calls to
 * them in order.
 *
 * 组合重试机制
 * @author Dave Syer
 * @author Michael Minella
 *
 */
@SuppressWarnings("serial")
public class CompositeRetryPolicy implements RetryPolicy {

	RetryPolicy[] policies = new RetryPolicy[0];

	private boolean optimistic = false;

	/**
	 * Setter for optimistic.
	 * @param optimistic should this retry policy be optimistic
	 */
	public void setOptimistic(boolean optimistic) {
		this.optimistic = optimistic;
	}

	/**
	 * Setter for policies.
	 * @param policies the {@link RetryPolicy} policies
	 */
	public void setPolicies(RetryPolicy[] policies) {
		this.policies = Arrays.asList(policies).toArray(new RetryPolicy[policies.length]);
	}

	/**
	 * Delegate to the policies that were in operation when the context was created. If
	 * any of them cannot retry then return false, otherwise return true.
	 * @param context the {@link RetryContext}
	 * @see org.springframework.retry.RetryPolicy#canRetry(org.springframework.retry.RetryContext)
	 */
	@Override
	public boolean canRetry(RetryContext context) {
		// 获取重试上下文集合
		RetryContext[] contexts = ((CompositeRetryContext) context).contexts;
		// 获取重试策略集合
		RetryPolicy[] policies = ((CompositeRetryContext) context).policies;

		// 重试标记
		boolean retryable = true;

		// 乐观的重试
		if (this.optimistic) {
			// 重试标记先设置为false
			retryable = false;
			// 循环所有重试上下文，通过重试策略对所有上下文进行是否可以重试的判断，只要有一个允许重试，重试标记就会设置为真
			for (int i = 0; i < contexts.length; i++) {
				if (policies[i].canRetry(contexts[i])) {
					retryable = true;
				}
			}
		}
		// 非乐观的重试
		else {
			// 循环所有重试上下文，通过重试策略对所有上下文进行是否可以重试的判断，只要有一个不允许重试，重试标记就会设置为假
			for (int i = 0; i < contexts.length; i++) {
				if (!policies[i].canRetry(contexts[i])) {
					retryable = false;
				}
			}
		}

		return retryable;
	}

	/**
	 * Delegate to the policies that were in operation when the context was created. If
	 * any of them fails to close the exception is propagated (and those later in the
	 * chain are closed before re-throwing).
	 *
	 * @see org.springframework.retry.RetryPolicy#close(org.springframework.retry.RetryContext)
	 * @param context the {@link RetryContext}
	 */
	@Override
	public void close(RetryContext context) {
		RetryContext[] contexts = ((CompositeRetryContext) context).contexts;
		RetryPolicy[] policies = ((CompositeRetryContext) context).policies;
		RuntimeException exception = null;
		for (int i = 0; i < contexts.length; i++) {
			try {
				policies[i].close(contexts[i]);
			}
			catch (RuntimeException e) {
				if (exception == null) {
					exception = e;
				}
			}
		}
		if (exception != null) {
			throw exception;
		}
	}

	/**
	 * Creates a new context that copies the existing policies and keeps a list of the
	 * contexts from each one.
	 *
	 * @see org.springframework.retry.RetryPolicy#open(RetryContext)
	 */
	@Override
	public RetryContext open(RetryContext parent) {
		List<RetryContext> list = new ArrayList<RetryContext>();
		for (RetryPolicy policy : this.policies) {
			list.add(policy.open(parent));
		}
		return new CompositeRetryContext(parent, list, this.policies);
	}

	/**
	 * Delegate to the policies that were in operation when the context was created.
	 *
	 * @see org.springframework.retry.RetryPolicy#close(org.springframework.retry.RetryContext)
	 */
	@Override
	public void registerThrowable(RetryContext context, Throwable throwable) {
		RetryContext[] contexts = ((CompositeRetryContext) context).contexts;
		RetryPolicy[] policies = ((CompositeRetryContext) context).policies;
		for (int i = 0; i < contexts.length; i++) {
			policies[i].registerThrowable(contexts[i], throwable);
		}
		((RetryContextSupport) context).registerThrowable(throwable);
	}

	private static class CompositeRetryContext extends RetryContextSupport {

		RetryContext[] contexts;

		RetryPolicy[] policies;

		public CompositeRetryContext(RetryContext parent, List<RetryContext> contexts, RetryPolicy[] policies) {
			super(parent);
			this.contexts = contexts.toArray(new RetryContext[contexts.size()]);
			this.policies = policies;
		}

	}

}
