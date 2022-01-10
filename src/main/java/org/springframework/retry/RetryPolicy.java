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

package org.springframework.retry;

import java.io.Serializable;

/**
 * A {@link RetryPolicy} is responsible for allocating and managing resources needed by
 * {@link RetryOperations}. The {@link RetryPolicy} allows retry operations to be aware of
 * their context. Context can be internal to the retry framework, e.g. to support nested
 * retries. Context can also be external, and the {@link RetryPolicy} provides a uniform
 * API for a range of different platforms for the external context.
 *
 * 重试机制(策略)
 * @author Dave Syer
 *
 */
public interface RetryPolicy extends Serializable {

	/**
	 * 判断是否可以重试
	 * @param context the current retry status
	 * @return true if the operation can proceed
	 */
	boolean canRetry(RetryContext context);

	/**
	 * Acquire resources needed for the retry operation. The callback is passed in so that
	 * marker interfaces can be used and a manager can collaborate with the callback to
	 * set up some state in the status token.
	 * 开始方法
	 * @param parent the parent context if we are in a nested retry.
	 * @return a {@link RetryContext} object specific to this policy.
	 *
	 */
	RetryContext open(RetryContext parent);

	/**
	 * 关闭方法
	 * @param context a retry status created by the {@link #open(RetryContext)} method of
	 * this policy.
	 */
	void close(RetryContext context);

	/**
	 * Called once per retry attempt, after the callback fails.
	 * 注册异常方法
	 * @param context the current status object.
	 * @param throwable the exception to throw
	 */
	void registerThrowable(RetryContext context, Throwable throwable);

}
