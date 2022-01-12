/*
 * Copyright 2014-2019 the original author or authors.
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

package org.springframework.retry.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for a method invocation that is retryable.
 *
 * @author Dave Syer
 * @author Artem Bilan
 * @author Gary Russell
 * @author Maksim Kita
 * @since 1.1
 *
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Retryable {

	/**
	 * Name of method in this class to use for recover. Method had to be marked with
	 * {@link Recover} annotation.
	 * @return the name of recover method
	 */
	String recover() default "";

	/**
	 * Retry interceptor bean name to be applied for retryable method. Is mutually
	 * exclusive with other attributes.
	 * @return the retry interceptor bean name
	 */
	String interceptor() default "";

	/**
	 * Exception types that are retryable. Synonym for includes(). Defaults to empty (and
	 * if excludes is also empty all exceptions are retried).
	 * 需要进行重试操作的异常集合
	 * @return exception types to retry
	 */
	Class<? extends Throwable>[] value() default {};

	/**
	 * Exception types that are retryable. Defaults to empty (and if excludes is also
	 * empty all exceptions are retried).
	 * 需要进行重试操作的异常集合
	 * @return exception types to retry
	 */
	Class<? extends Throwable>[] include() default {};

	/**
	 * Exception types that are not retryable. Defaults to empty (and if includes is also
	 * empty all exceptions are retried). If includes is empty but excludes is not, all
	 * not excluded exceptions are retried
	 * 不需要进行重试操作的异常集合
	 * @return exception types not to retry
	 */
	Class<? extends Throwable>[] exclude() default {};

	/**
	 * A unique label for statistics reporting. If not provided the caller may choose to
	 * ignore it, or provide a default.
	 *
	 * 标记接口
	 * @return the label for the statistics
	 */
	String label() default "";

	/**
	 * Flag to say that the retry is stateful: i.e. exceptions are re-thrown, but the
	 * retry policy is applied with the same policy to subsequent invocations with the
	 * same arguments. If false then retryable exceptions are not re-thrown.
	 *
	 *
	 *
	 * 表示重试是有状态的标志
	 * @return true if retry is stateful, default false
	 */
	boolean stateful() default false;

	/**
	 * 最大重试次数
	 * @return the maximum number of attempts (including the first failure), defaults to 3
	 */
	int maxAttempts() default 3;

	/**
	 * @return an expression evaluated to the maximum number of attempts (including the
	 * first failure), defaults to 3 Overrides {@link #maxAttempts()}.
	 * @since 1.2
	 */
	String maxAttemptsExpression() default "";

	/**
	 * Specify the backoff properties for retrying this operation. The default is a simple
	 * {@link Backoff} specification with no properties - see it's documentation for
	 * defaults.
	 *
	 * 重试回退属性
	 * @return a backoff specification
	 */
	Backoff backoff() default @Backoff();

	/**
	 * Specify an expression to be evaluated after the
	 * {@code SimpleRetryPolicy.canRetry()} returns true - can be used to conditionally
	 * suppress the retry. Only invoked after an exception is thrown. The root object for
	 * the evaluation is the last {@code Throwable}. Other beans in the context can be
	 * referenced. For example: <pre class=code>
	 *  {@code "message.contains('you can retry this')"}.
	 * </pre> and <pre class=code>
	 *  {@code "@someBean.shouldRetry(#root)"}.
	 * </pre>
	 * @return the expression.
	 * @since 1.2
	 *
	 * 异常表达式
	 */
	String exceptionExpression() default "";

	/**
	 * Bean names of retry listeners to use instead of default ones defined in Spring
	 * context
	 *
	 * 监听器名称集合
	 * @return retry listeners bean names
	 */
	String[] listeners() default {};

}
