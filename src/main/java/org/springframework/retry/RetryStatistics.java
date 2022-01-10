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

/**
 * Interface for statistics reporting of retry attempts. Counts the number of retry
 * attempts, successes, errors (including retries), and aborts.
 *
 *
 * 重试统计接口
 * @author Dave Syer
 *
 */
public interface RetryStatistics {

	/**
	 * 已完成的成功重试次数。
	 * @return the number of completed successful retry attempts.
	 */
	int getCompleteCount();

	/**
	 * 获取开始的重试次数
	 * Get the number of times a retry block has been entered, irrespective of how many
	 * times the operation was retried.
	 * @return the number of retry blocks started.
	 */
	int getStartedCount();

	/**
	 * 获取检测到的错误数，无论它们是否导致重试。
	 * Get the number of errors detected, whether or not they resulted in a retry.
	 * @return the number of errors detected.
	 */
	int getErrorCount();

	/**
	 * 获取块未能成功完成的次数
	 * Get the number of times a block failed to complete successfully, even after retry.
	 * @return the number of retry attempts that failed overall.
	 */
	int getAbortCount();

	/**
	 * 获取应用恢复回调的次数
	 * Get the number of times a recovery callback was applied.
	 * @return the number of recovered attempts.
	 */
	int getRecoveryCount();

	/**
	 * 统计标识符
	 * Get an identifier for the retry block for reporting purposes.
	 * @return an identifier for the block.
	 */
	String getName();

}
