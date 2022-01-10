/*
 * Copyright 2014 the original author or authors.
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.aop.Advice;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.IntroductionAdvisor;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.aop.support.annotation.AnnotationClassFilter;
import org.springframework.aop.support.annotation.AnnotationMethodMatcher;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.OrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.interceptor.MethodArgumentsKeyGenerator;
import org.springframework.retry.interceptor.NewMethodArgumentsIdentifier;
import org.springframework.retry.policy.RetryContextCache;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;

/**
 * Basic configuration for <code>@Retryable</code> processing. For stateful retry, if
 * there is a unique bean elsewhere in the context of type {@link RetryContextCache},
 * {@link MethodArgumentsKeyGenerator} or {@link NewMethodArgumentsIdentifier} it will be
 * used by the corresponding retry interceptor (otherwise sensible defaults are adopted).
 *
 * @author Dave Syer
 * @author Artem Bilan
 * @since 1.1
 *
 */
@SuppressWarnings("serial")
@Component
public class RetryConfiguration extends AbstractPointcutAdvisor
		implements IntroductionAdvisor, BeanFactoryAware, InitializingBean {

	/**
	 * 顾问
	 */
	private Advice advice;

	/**
	 * 切点
	 */
	private Pointcut pointcut;

	/**
	 * 重试上下文缓存
	 */
	private RetryContextCache retryContextCache;

	/**
	 * 重试监听器
	 */
	private List<RetryListener> retryListeners;

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

	@Override
	public void afterPropertiesSet() throws Exception {
		// 寻找RetryContextCache类型的bean
		retryContextCache = findBean(RetryContextCache.class);
		// 寻找MethodArgumentsKeyGenerator类型的bean
		methodArgumentsKeyGenerator = findBean(MethodArgumentsKeyGenerator.class);
		// 寻找NewMethodArgumentsIdentifier类型的bean
		newMethodArgumentsIdentifier = findBean(NewMethodArgumentsIdentifier.class);
		// 寻找RetryListener类型的bean
		retryListeners = findBeans(RetryListener.class);
		// 寻找Sleeper类型的bean
		sleeper = findBean(Sleeper.class);
		// 添加受扫描的注解
		Set<Class<? extends Annotation>> retryableAnnotationTypes = new LinkedHashSet<Class<? extends Annotation>>(
				1);
		retryableAnnotationTypes.add(Retryable.class);
		// 构造切点
		this.pointcut = buildPointcut(retryableAnnotationTypes);
		// 构造顾问
		this.advice = buildAdvice();
		// 如果顾问对象实现了BeanFactoryAware接口向其设置bean工厂
		if (this.advice instanceof BeanFactoryAware) {
			((BeanFactoryAware) this.advice).setBeanFactory(beanFactory);
		}
	}

	private <T> List<T> findBeans(Class<? extends T> type) {
		if (beanFactory instanceof ListableBeanFactory) {
			ListableBeanFactory listable = (ListableBeanFactory) beanFactory;
			if (listable.getBeanNamesForType(type).length == 1) {
				ArrayList<T> list = new ArrayList<T>(listable.getBeansOfType(type).values());
				OrderComparator.sort(list);
				return list;
			}
		}
		return null;
	}

	private <T> T findBean(Class<? extends T> type) {
		if (beanFactory instanceof ListableBeanFactory) {
			ListableBeanFactory listable = (ListableBeanFactory) beanFactory;
			if (listable.getBeanNamesForType(type).length == 1) {
				return listable.getBean(type);
			}
		}
		return null;
	}

	/**
	 * Set the {@code BeanFactory} to be used when looking up executors by qualifier.
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public ClassFilter getClassFilter() {
		return pointcut.getClassFilter();
	}

	@Override
	public Class<?>[] getInterfaces() {
		return new Class[] { org.springframework.retry.interceptor.Retryable.class };
	}

	@Override
	public void validateInterfaces() throws IllegalArgumentException {
	}

	@Override
	public Advice getAdvice() {
		return this.advice;
	}

	@Override
	public Pointcut getPointcut() {
		return this.pointcut;
	}

	// 构造顾问对象
	protected Advice buildAdvice() {
		// 创建AnnotationAwareRetryOperationsInterceptor对象,理解为方法拦截器
		AnnotationAwareRetryOperationsInterceptor interceptor = new AnnotationAwareRetryOperationsInterceptor();
		// 设置属性
		if (retryContextCache != null) {
			interceptor.setRetryContextCache(retryContextCache);
		}
		if (retryListeners != null) {
			interceptor.setListeners(retryListeners);
		}
		if (methodArgumentsKeyGenerator != null) {
			interceptor.setKeyGenerator(methodArgumentsKeyGenerator);
		}
		if (newMethodArgumentsIdentifier != null) {
			interceptor.setNewItemIdentifier(newMethodArgumentsIdentifier);
		}
		if (sleeper != null) {
			interceptor.setSleeper(sleeper);
		}
		return interceptor;
	}

	/**
	 * Calculate a pointcut for the given retry annotation types, if any.
	 *
	 * @param retryAnnotationTypes the retry annotation types to introspect
	 * @return the applicable Pointcut object, or {@code null} if none
	 */
	protected Pointcut buildPointcut(Set<Class<? extends Annotation>> retryAnnotationTypes) {
		// 切点对象
		ComposablePointcut result = null;
		// 循环切点注解集合
		for (Class<? extends Annotation> retryAnnotationType : retryAnnotationTypes) {
			// 构造切点对象
			Pointcut filter = new AnnotationClassOrMethodPointcut(retryAnnotationType);
			if (result == null) {
				result = new ComposablePointcut(filter);
			} else {
				result.union(filter);
			}
		}
		return result;
	}

	private final class AnnotationClassOrMethodPointcut extends StaticMethodMatcherPointcut {

		private final MethodMatcher methodResolver;

		AnnotationClassOrMethodPointcut(Class<? extends Annotation> annotationType) {
			this.methodResolver = new AnnotationMethodMatcher(annotationType);
			setClassFilter(new AnnotationClassOrMethodFilter(annotationType));
		}

		@Override
		public boolean matches(Method method, Class<?> targetClass) {
			return getClassFilter().matches(targetClass) || this.methodResolver.matches(method, targetClass);
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof AnnotationClassOrMethodPointcut)) {
				return false;
			}
			AnnotationClassOrMethodPointcut otherAdvisor = (AnnotationClassOrMethodPointcut) other;
			return ObjectUtils.nullSafeEquals(this.methodResolver, otherAdvisor.methodResolver);
		}

	}

	private final class AnnotationClassOrMethodFilter extends AnnotationClassFilter {

		private final AnnotationMethodsResolver methodResolver;

		AnnotationClassOrMethodFilter(Class<? extends Annotation> annotationType) {
			super(annotationType, true);
			this.methodResolver = new AnnotationMethodsResolver(annotationType);
		}

		@Override
		public boolean matches(Class<?> clazz) {
			return super.matches(clazz) || this.methodResolver.hasAnnotatedMethods(clazz);
		}

	}

	private static class AnnotationMethodsResolver {

		private Class<? extends Annotation> annotationType;

		public AnnotationMethodsResolver(Class<? extends Annotation> annotationType) {
			this.annotationType = annotationType;
		}

		public boolean hasAnnotatedMethods(Class<?> clazz) {
			final AtomicBoolean found = new AtomicBoolean(false);
			ReflectionUtils.doWithMethods(clazz, new MethodCallback() {
				@Override
				public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
					if (found.get()) {
						return;
					}
					Annotation annotation = AnnotationUtils.findAnnotation(method, annotationType);
					if (annotation != null) {
						found.set(true);
					}
				}
			});
			return found.get();
		}

	}

}
