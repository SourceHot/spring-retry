/*
 * Copyright 2014-2018 the original author or authors.
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.naming.OperationNotSupportedException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.IntroductionInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.backoff.UniformRandomBackOffPolicy;
import org.springframework.retry.interceptor.FixedKeyGenerator;
import org.springframework.retry.interceptor.MethodArgumentsKeyGenerator;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.retry.interceptor.NewMethodArgumentsIdentifier;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.policy.CircuitBreakerRetryPolicy;
import org.springframework.retry.policy.ExpressionRetryPolicy;
import org.springframework.retry.policy.MapRetryContextCache;
import org.springframework.retry.policy.RetryContextCache;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * Interceptor that parses the retry metadata on the method it is invoking and delegates to an
 * appropriate RetryOperationsInterceptor.
 *
 * @author Dave Syer
 * @author Artem Bilan
 * @author Gary Russell
 * @since 1.1
 */
public class AnnotationAwareRetryOperationsInterceptor implements IntroductionInterceptor,
		BeanFactoryAware {

	/**
	 * 模板解析上下文
	 */
	private static final TemplateParserContext PARSER_CONTEXT = new TemplateParserContext();

	/**
	 * Spring EL表达式解析器
	 */
	private static final SpelExpressionParser PARSER = new SpelExpressionParser();

	/**
	 * 空的方法拦截器，直接抛出异常
	 */
	private static final MethodInterceptor NULL_INTERCEPTOR = new MethodInterceptor() {
		@Override
		public Object invoke(MethodInvocation methodInvocation) throws Throwable {
			throw new OperationNotSupportedException("Not supported");
		}
	};

	/**
	 * 评估上下文
	 */
	private final StandardEvaluationContext evaluationContext = new StandardEvaluationContext();

	/**
	 * 对象-> 方法和方法拦截器的映射
	 */
	private final ConcurrentReferenceHashMap<Object, ConcurrentMap<Method, MethodInterceptor>> delegates = new ConcurrentReferenceHashMap<Object, ConcurrentMap<Method, MethodInterceptor>>();
	/**
	 * 重试上下文缓存
	 */
	private RetryContextCache retryContextCache = new MapRetryContextCache();
	/**
	 * 方法参数密钥生成器
	 */
	private MethodArgumentsKeyGenerator methodArgumentsKeyGenerator;
	/**
	 * 用于区分参数是否被处理
	 */
	private NewMethodArgumentsIdentifier newMethodArgumentsIdentifier;
	/**
	 * 暂停接口
	 */
	private Sleeper sleeper;
	/**
	 * bean工厂
	 */
	private BeanFactory beanFactory;
	/**
	 * 重试监听器
	 */
	private RetryListener[] globalListeners;

	/**
	 * @param sleeper the sleeper to set
	 */
	public void setSleeper(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	/**
	 * Public setter for the {@link RetryContextCache}.
	 * @param retryContextCache the {@link RetryContextCache} to set.
	 */
	public void setRetryContextCache(RetryContextCache retryContextCache) {
		this.retryContextCache = retryContextCache;
	}

	/**
	 * @param methodArgumentsKeyGenerator the {@link MethodArgumentsKeyGenerator}
	 */
	public void setKeyGenerator(MethodArgumentsKeyGenerator methodArgumentsKeyGenerator) {
		this.methodArgumentsKeyGenerator = methodArgumentsKeyGenerator;
	}

	/**
	 * @param newMethodArgumentsIdentifier the {@link NewMethodArgumentsIdentifier}
	 */
	public void setNewItemIdentifier(NewMethodArgumentsIdentifier newMethodArgumentsIdentifier) {
		this.newMethodArgumentsIdentifier = newMethodArgumentsIdentifier;
	}

	/**
	 * Default retry listeners to apply to all operations.
	 * @param globalListeners the default listeners
	 */
	public void setListeners(Collection<RetryListener> globalListeners) {
		ArrayList<RetryListener> retryListeners = new ArrayList<RetryListener>(globalListeners);
		AnnotationAwareOrderComparator.sort(retryListeners);
		this.globalListeners = retryListeners.toArray(new RetryListener[0]);
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
		this.evaluationContext.setBeanResolver(new BeanFactoryResolver(beanFactory));
	}

	@Override
	public boolean implementsInterface(Class<?> intf) {
		return org.springframework.retry.interceptor.Retryable.class.isAssignableFrom(intf);
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		// 在成员变量delegates中搜索方法拦截器
		MethodInterceptor delegate = getDelegate(invocation.getThis(), invocation.getMethod());
		if (delegate != null) {
			return delegate.invoke(invocation);
		} else {
			return invocation.proceed();
		}
	}

	private MethodInterceptor getDelegate(Object target, Method method) {
		// 从成员变量delegates中根据目标对象获取方法与方法拦截器映射
		ConcurrentMap<Method, MethodInterceptor> cachedMethods = this.delegates.get(target);
		// 如果方法与方法拦截器映射为空则初始化对象
		if (cachedMethods == null) {
			cachedMethods = new ConcurrentHashMap<Method, MethodInterceptor>();
		}
		// 从方法与方法拦截器映射中获取方法对应的方法拦截器
		MethodInterceptor delegate = cachedMethods.get(method);
		// 方法拦截器为空
		if (delegate == null) {
			// 先将空拦截器设置
			MethodInterceptor interceptor = NULL_INTERCEPTOR;
			// 在当前方法上搜索Retryable注解
			Retryable retryable = AnnotatedElementUtils.findMergedAnnotation(method, Retryable.class);
			// Retryable注解不存在
			if (retryable == null) {
				// 在方法所在的类上搜索Retryable注解
				retryable = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(),
						Retryable.class);
			}
			// Retryable注解不存在
			if (retryable == null) {
				// 在target对象上搜索
				retryable = findAnnotationOnTarget(target, method, Retryable.class);
			}
			// Retryable注解存在
			if (retryable != null) {
				// 提取Retryable注解中的interceptor数据判断是否为空，不为空的情况下寻找对应实例
				if (StringUtils.hasText(retryable.interceptor())) {
					interceptor = this.beanFactory.getBean(retryable.interceptor(), MethodInterceptor.class);
				}
				// 判断Retryable注解的stateful返回结果是否为真
				else if (retryable.stateful()) {
					// 构造支持stateful的方法拦截器
					interceptor = getStatefulInterceptor(target, method, retryable);
				}
				// 判断Retryable注解的stateful返回结果是否为假
				else {
					// 构造无状态的方法拦截器
					interceptor = getStatelessInterceptor(target, method, retryable);
				}
			}
			// 向方法与方法拦截器映射中加入数据
			cachedMethods.putIfAbsent(method, interceptor);
			// 获取方法拦截器
			delegate = cachedMethods.get(method);
		}
		// 放入到成员变量delegates中
		this.delegates.putIfAbsent(target, cachedMethods);
		// 返回方法拦截器
		return delegate == NULL_INTERCEPTOR ? null : delegate;
	}

	private <A extends Annotation> A findAnnotationOnTarget(Object target, Method method, Class<A> annotation) {

		try {
			// 在target的类中搜索method对应的方法
			Method targetMethod = target.getClass()
					.getMethod(method.getName(), method.getParameterTypes());
			// 在方法上搜索注解
			A retryable = AnnotatedElementUtils.findMergedAnnotation(targetMethod, annotation);
			// 在方法所在类上搜索注解
			if (retryable == null) {
				retryable = AnnotatedElementUtils.findMergedAnnotation(targetMethod.getDeclaringClass(),
						annotation);
			}

			return retryable;
		} catch (Exception e) {
			return null;
		}
	}

	private MethodInterceptor getStatelessInterceptor(Object target, Method method,
			Retryable retryable) {
		// 创建RetryTemplate对象
		RetryTemplate template = createTemplate(retryable.listeners());
		// 设置RetryPolicy
		template.setRetryPolicy(getRetryPolicy(retryable));
		// 设置BackOffPolicy
		template.setBackOffPolicy(getBackoffPolicy(retryable.backoff()));
		// 构造方法拦截器
		return RetryInterceptorBuilder.stateless().retryOperations(template).label(retryable.label())
				.recoverer(getRecoverer(target, method)).build();
	}

	// 构造支持stateful的方法拦截器
	private MethodInterceptor getStatefulInterceptor(Object target, Method method,
			Retryable retryable) {
		// 创建RetryTemplate对象
		RetryTemplate template = createTemplate(retryable.listeners());
		// 为RetryTemplate对象设置重试上下文缓存
		template.setRetryContextCache(this.retryContextCache);

		// 在方法上搜索CircuitBreaker注解
		CircuitBreaker circuit = AnnotatedElementUtils.findMergedAnnotation(method,
				CircuitBreaker.class);
		// CircuitBreaker注解为空
		if (circuit == null) {
			// 在target对象上搜索CircuitBreaker注解
			circuit = findAnnotationOnTarget(target, method, CircuitBreaker.class);
		}
		// CircuitBreaker注解不为空
		if (circuit != null) {
			// 通过CircuitBreaker注解中获取RetryPolicy接口的实现类
			RetryPolicy policy = getRetryPolicy(circuit);
			// 创建CircuitBreakerRetryPolicy对象
			CircuitBreakerRetryPolicy breaker = new CircuitBreakerRetryPolicy(policy);
			// 为CircuitBreakerRetryPolicy对象设置openTimeout属性
			breaker.setOpenTimeout(getOpenTimeout(circuit));
			// 为CircuitBreakerRetryPolicy对象设置resetTimeout属性
			breaker.setResetTimeout(getResetTimeout(circuit));
			// 为RetryTemplate对象设置retryPolicy属性
			template.setRetryPolicy(breaker);
			// 为RetryTemplate对象设置backOffPolicy属性
			template.setBackOffPolicy(new NoBackOffPolicy());
			// 获取CircuitBreaker注解的label属性
			String label = circuit.label();
			// 如果label属性为空
			if (!StringUtils.hasText(label)) {
				// 将label设置为方法签名
				label = method.toGenericString();
			}
			// 构造方法拦截器返回
			return RetryInterceptorBuilder.circuitBreaker().keyGenerator(new FixedKeyGenerator("circuit"))
					.retryOperations(template).recoverer(getRecoverer(target, method)).label(label).build();
		}
		// 通过CircuitBreaker注解中获取RetryPolicy接口的实现类
		RetryPolicy policy = getRetryPolicy(retryable);
		// 为RetryTemplate设置retryPolicy属性
		template.setRetryPolicy(policy);
		// 为RetryTemplate设置backOffPolicy属性
		template.setBackOffPolicy(getBackoffPolicy(retryable.backoff()));
		// 在Retryable注解中获取label属性
		String label = retryable.label();
		// 构造方法拦截器返回
		return RetryInterceptorBuilder.stateful().keyGenerator(this.methodArgumentsKeyGenerator)
				.newMethodArgumentsIdentifier(this.newMethodArgumentsIdentifier).retryOperations(template)
				.label(label)
				.recoverer(getRecoverer(target, method)).build();
	}

	private long getOpenTimeout(CircuitBreaker circuit) {
		if (StringUtils.hasText(circuit.openTimeoutExpression())) {
			Long value = PARSER.parseExpression(resolve(circuit.openTimeoutExpression()), PARSER_CONTEXT)
					.getValue(Long.class);
			if (value != null) {
				return value;
			}
		}
		return circuit.openTimeout();
	}

	private long getResetTimeout(CircuitBreaker circuit) {
		if (StringUtils.hasText(circuit.resetTimeoutExpression())) {
			Long value = PARSER.parseExpression(resolve(circuit.resetTimeoutExpression()), PARSER_CONTEXT)
					.getValue(Long.class);
			if (value != null) {
				return value;
			}
		}
		return circuit.resetTimeout();
	}

	private RetryTemplate createTemplate(String[] listenersBeanNames) {
		// 创建RetryTemplate对象
		RetryTemplate template = new RetryTemplate();
		// 如果监听器器名称集合数量大于0
		if (listenersBeanNames.length > 0) {
			// 从容器中找到监听器名称集合对应的bean实例放入到RetryTemplate对象中
			template.setListeners(getListenersBeans(listenersBeanNames));
		}
		// 全局的监听器不为空
		else if (this.globalListeners != null) {
			// 将全局监听器设置到RetryTemplate对象中
			template.setListeners(this.globalListeners);
		}
		return template;
	}

	private RetryListener[] getListenersBeans(String[] listenersBeanNames) {
		RetryListener[] listeners = new RetryListener[listenersBeanNames.length];
		for (int i = 0; i < listeners.length; i++) {
			listeners[i] = this.beanFactory.getBean(listenersBeanNames[i], RetryListener.class);
		}
		return listeners;
	}

	/**
	 * todo:
	 *
	 * @param target
	 * @param method
	 * @return
	 */
	private MethodInvocationRecoverer<?> getRecoverer(Object target, Method method) {
		// 判断target
		if (target instanceof MethodInvocationRecoverer) {
			return (MethodInvocationRecoverer<?>) target;
		}
		final AtomicBoolean foundRecoverable = new AtomicBoolean(false);
		ReflectionUtils.doWithMethods(target.getClass(), new MethodCallback() {
			@Override
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				if (AnnotatedElementUtils.findMergedAnnotation(method, Recover.class) != null) {
					foundRecoverable.set(true);
				}
			}
		});

		if (!foundRecoverable.get()) {
			return null;
		}
		return new RecoverAnnotationRecoveryHandler<Object>(target, method);
	}

	/**
	 * todo:
	 * @param retryable
	 * @return
	 */
	private RetryPolicy getRetryPolicy(Annotation retryable) {
		Map<String, Object> attrs = AnnotationUtils.getAnnotationAttributes(retryable);
		@SuppressWarnings("unchecked")
		Class<? extends Throwable>[] includes = (Class<? extends Throwable>[]) attrs.get("value");
		String exceptionExpression = (String) attrs.get("exceptionExpression");
		boolean hasExpression = StringUtils.hasText(exceptionExpression);
		if (includes.length == 0) {
			@SuppressWarnings("unchecked")
			Class<? extends Throwable>[] value = (Class<? extends Throwable>[]) attrs.get("include");
			includes = value;
		}
		@SuppressWarnings("unchecked")
		Class<? extends Throwable>[] excludes = (Class<? extends Throwable>[]) attrs.get("exclude");
		Integer maxAttempts = (Integer) attrs.get("maxAttempts");
		String maxAttemptsExpression = (String) attrs.get("maxAttemptsExpression");
		if (StringUtils.hasText(maxAttemptsExpression)) {
			maxAttempts = PARSER.parseExpression(resolve(maxAttemptsExpression), PARSER_CONTEXT)
					.getValue(this.evaluationContext, Integer.class);
		}
		if (includes.length == 0 && excludes.length == 0) {
			SimpleRetryPolicy simple = hasExpression
					? new ExpressionRetryPolicy(resolve(exceptionExpression)).withBeanFactory(this.beanFactory)
					: new SimpleRetryPolicy();
			simple.setMaxAttempts(maxAttempts);
			return simple;
		}
		Map<Class<? extends Throwable>, Boolean> policyMap = new HashMap<Class<? extends Throwable>, Boolean>();
		for (Class<? extends Throwable> type : includes) {
			policyMap.put(type, true);
		}
		for (Class<? extends Throwable> type : excludes) {
			policyMap.put(type, false);
		}
		boolean retryNotExcluded = includes.length == 0;
		if (hasExpression) {
			return new ExpressionRetryPolicy(maxAttempts, policyMap, true, exceptionExpression, retryNotExcluded)
					.withBeanFactory(this.beanFactory);
		}
		else {
			return new SimpleRetryPolicy(maxAttempts, policyMap, true, retryNotExcluded);
		}
	}

	/**
	 * todo:
	 * @param backoff
	 * @return
	 */
	private BackOffPolicy getBackoffPolicy(Backoff backoff) {
		long min = backoff.delay() == 0 ? backoff.value() : backoff.delay();
		if (StringUtils.hasText(backoff.delayExpression())) {
			min = PARSER.parseExpression(resolve(backoff.delayExpression()), PARSER_CONTEXT)
					.getValue(this.evaluationContext, Long.class);
		}
		long max = backoff.maxDelay();
		if (StringUtils.hasText(backoff.maxDelayExpression())) {
			max = PARSER.parseExpression(resolve(backoff.maxDelayExpression()), PARSER_CONTEXT)
					.getValue(this.evaluationContext, Long.class);
		}
		double multiplier = backoff.multiplier();
		if (StringUtils.hasText(backoff.multiplierExpression())) {
			multiplier = PARSER.parseExpression(resolve(backoff.multiplierExpression()), PARSER_CONTEXT)
					.getValue(this.evaluationContext, Double.class);
		}
		if (multiplier > 0) {
			ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
			if (backoff.random()) {
				policy = new ExponentialRandomBackOffPolicy();
			}
			policy.setInitialInterval(min);
			policy.setMultiplier(multiplier);
			policy.setMaxInterval(max > min ? max : ExponentialBackOffPolicy.DEFAULT_MAX_INTERVAL);
			if (this.sleeper != null) {
				policy.setSleeper(this.sleeper);
			}
			return policy;
		}
		if (max > min) {
			UniformRandomBackOffPolicy policy = new UniformRandomBackOffPolicy();
			policy.setMinBackOffPeriod(min);
			policy.setMaxBackOffPeriod(max);
			if (this.sleeper != null) {
				policy.setSleeper(this.sleeper);
			}
			return policy;
		}
		FixedBackOffPolicy policy = new FixedBackOffPolicy();
		policy.setBackOffPeriod(min);
		if (this.sleeper != null) {
			policy.setSleeper(this.sleeper);
		}
		return policy;
	}

	/**
	 * Resolve the specified value if possible.
	 *
	 * @see ConfigurableBeanFactory#resolveEmbeddedValue
	 */
	private String resolve(String value) {
		if (this.beanFactory != null && this.beanFactory instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) this.beanFactory).resolveEmbeddedValue(value);
		}
		return value;
	}

}
