/*
 * Copyright 2013-2019 the original author or authors.
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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.springframework.classify.SubclassClassifier;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.ExhaustedRetryException;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * A recoverer for method invocations based on the <code>@Recover</code> annotation. A
 * suitable recovery method is one with a Throwable type as the first parameter and the
 * same return type and arguments as the method that failed. The Throwable first argument
 * is optional and if omitted the method is treated as a default (called when there are no
 * other matches). Generally the best matching method is chosen based on the type of the
 * first parameter and the type of the exception being handled. The closest match in the
 * class hierarchy is chosen, so for instance if an IllegalArgumentException is being
 * handled and there is a method whose first argument is RuntimeException, then it will be
 * preferred over a method whose first argument is Throwable.
 *
 * @author Dave Syer
 * @author Josh Long
 * @author Aldo Sinanaj
 * @author Randell Callahan
 * @author Nathanaël Roberts
 * @author Maksim Kita
 * @author Gary Russell
 * @param <T> the type of the return value from the recovery
 */
public class RecoverAnnotationRecoveryHandler<T> implements MethodInvocationRecoverer<T> {

	/**
	 * 分类器
	 */
	private SubclassClassifier<Throwable, Method> classifier = new SubclassClassifier<Throwable, Method>();

	/**
	 * 方法映射表
	 */
	private Map<Method, SimpleMetadata> methods = new HashMap<Method, SimpleMetadata>();

	/**
	 * 目标对象
	 */
	private Object target;

	/**
	 * recover方法名称
	 */
	private String recoverMethodName;

	public RecoverAnnotationRecoveryHandler(Object target, Method method) {
		this.target = target;
		init(target, method);
	}

	@Override
	public T recover(Object[] args, Throwable cause) {
		// 根据参数和异常选择方法
		Method method = findClosestMatch(args, cause.getClass());
		// 搜索的方法结果为空抛出异常
		if (method == null) {
			throw new ExhaustedRetryException("Cannot locate recovery method", cause);
		}
		// 在方法映射表重找到元信息
		SimpleMetadata meta = this.methods.get(method);
		// 元信息中获取参数集合
		Object[] argsToUse = meta.getArgs(cause, args);
		// 获取方法的accessible标记
		boolean methodAccessible = method.isAccessible();
		try {
			// 标记accessible为true
			ReflectionUtils.makeAccessible(method);
			// 执行method方法
			@SuppressWarnings("unchecked")
			T result = (T) ReflectionUtils.invokeMethod(method, this.target, argsToUse);
			return result;
		} finally {
			// 方法的accessible标记不相同的情况下重新覆盖
			if (methodAccessible != method.isAccessible()) {
				method.setAccessible(methodAccessible);
			}
		}
	}

	private Method findClosestMatch(Object[] args, Class<? extends Throwable> cause) {
		// 返回结果
		Method result = null;

		// recover方法名称为空的情况
		if (StringUtils.isEmpty(this.recoverMethodName)) {
			// 最小值
			int min = Integer.MAX_VALUE;
			// 遍历方法映射表寻找方法
			for (Map.Entry<Method, SimpleMetadata> entry : this.methods.entrySet()) {
				// 获取方法
				Method method = entry.getKey();
				// 获取元数据
				SimpleMetadata meta = entry.getValue();
				// 获取元数据中的异常对象
				Class<? extends Throwable> type = meta.getType();
				// 如果异常对象为空将采用Throwable
				if (type == null) {
					type = Throwable.class;
				}
				// 判断输入的异常是否是元数据中的异常子类
				if (type.isAssignableFrom(cause)) {
					// 计算继承层级
					int distance = calculateDistance(cause, type);
					// 层级小于最小数值
					if (distance < min) {
						// 将最小值数据修改为继承层级
						min = distance;
						// 将返回结果修改为当前遍历中的方法
						result = method;
					}
					// 层级和最小数值相同
					else if (distance == min) {
						// 参数比较
						boolean parametersMatch = compareParameters(args, meta.getArgCount(),
								method.getParameterTypes());
						// 如果参数比较通过将返回结果修改为当前遍历中的方法
						if (parametersMatch) {
							result = method;
						}
					}
				}
			}
		}
		// recover方法名称不为空的情况
		else {
			// 遍历方法映射表寻找方法
			for (Map.Entry<Method, SimpleMetadata> entry : this.methods.entrySet()) {
				// 获取方法
				Method method = entry.getKey();
				// 判断当前处理的方法是否和成员变量recoverMethodName相同
				if (method.getName().equals(this.recoverMethodName)) {
					// 提取元数据
					SimpleMetadata meta = entry.getValue();
					// 判断输入的异常是否是元数据中的异常子类，同时进行参数比较
					if (meta.type.isAssignableFrom(cause)
							&& compareParameters(args, meta.getArgCount(), method.getParameterTypes())) {
						result = method;
						break;
					}
				}
			}
		}
		return result;
	}

	private int calculateDistance(Class<? extends Throwable> cause, Class<? extends Throwable> type) {
		int result = 0;
		Class<?> current = cause;
		while (current != type && current != Throwable.class) {
			result++;
			current = current.getSuperclass();
		}
		return result;
	}

	private boolean compareParameters(Object[] args, int argCount, Class<?>[] parameterTypes) {
		if (argCount == (args.length + 1)) {
			int startingIndex = 0;
			if (parameterTypes.length > 0 && Throwable.class.isAssignableFrom(parameterTypes[0])) {
				startingIndex = 1;
			}
			for (int i = startingIndex; i < parameterTypes.length; i++) {
				final Object argument = i - startingIndex < args.length ? args[i - startingIndex] : null;
				if (argument == null) {
					continue;
				}
				if (!parameterTypes[i].isAssignableFrom(argument.getClass())) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private void init(final Object target, Method method) {
		// 构造异常和方法的映射表
		final Map<Class<? extends Throwable>, Method> types = new HashMap<Class<? extends Throwable>, Method>();
		final Method failingMethod = method;
		// 在传入的方法对象中搜索Retryable注解
		Retryable retryable = AnnotationUtils.findAnnotation(method, Retryable.class);
		// 注解Retryable不为空的情况下将recover属性设置到成员变量recoverMethodName中
		if (retryable != null) {
			this.recoverMethodName = retryable.recover();
		}
		ReflectionUtils.doWithMethods(target.getClass(), new MethodCallback() {
			@Override
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				// 在方法上寻找Recover注解
				Recover recover = AnnotationUtils.findAnnotation(method, Recover.class);
				// 方法上Recover注解为空的情况在target对象上寻找
				if (recover == null) {
					recover = findAnnotationOnTarget(target, method);
				}
				// Recover注解存在并且当前方法返回值和failingMethod方法返回值同源
				if (recover != null && method.getReturnType()
						.isAssignableFrom(failingMethod.getReturnType())) {
					// 提取参数类型
					Class<?>[] parameterTypes = method.getParameterTypes();
					// 参数类型数量大于0，并且第一个参数和Throwable同源
					if (parameterTypes.length > 0 && Throwable.class.isAssignableFrom(parameterTypes[0])) {
						// 提取第一个参数类型将其放入到异常和方法的映射表
						@SuppressWarnings("unchecked")
						Class<? extends Throwable> type = (Class<? extends Throwable>) parameterTypes[0];
						types.put(type, method);
						// 成员变量methods的设置
						RecoverAnnotationRecoveryHandler.this.methods.put(method,
								new SimpleMetadata(parameterTypes.length, type));
					}
					// 其他情况
					else {
						// 设置成员变量classifier和methods
						RecoverAnnotationRecoveryHandler.this.classifier.setDefaultValue(method);
						RecoverAnnotationRecoveryHandler.this.methods.put(method,
								new SimpleMetadata(parameterTypes.length, null));
					}
				}
			}
		});
		// 处理成员变量classifier和methods
		this.classifier.setTypeMap(types);
		optionallyFilterMethodsBy(failingMethod.getReturnType());
	}

	private Recover findAnnotationOnTarget(Object target, Method method) {
		try {
			Method targetMethod = target.getClass().getMethod(method.getName(), method.getParameterTypes());
			return AnnotationUtils.findAnnotation(targetMethod, Recover.class);
		}
		catch (Exception e) {
			return null;
		}
	}

	private void optionallyFilterMethodsBy(Class<?> returnClass) {
		// 构造存储容器
		Map<Method, SimpleMetadata> filteredMethods = new HashMap<Method, SimpleMetadata>();
		// 循环方法映射表
		for (Method method : this.methods.keySet()) {
			// 判断返回值类型是否和传入的返回值类型相同，如果是则加入到存储容器中
			if (method.getReturnType() == returnClass) {
				filteredMethods.put(method, this.methods.get(method));
			}
		}
		// 存储容器数量大于0的情况下将成员变量methods进行覆盖
		if (filteredMethods.size() > 0) {
			this.methods = filteredMethods;
		}
	}

	private static class SimpleMetadata {

		private int argCount;

		private Class<? extends Throwable> type;

		public SimpleMetadata(int argCount, Class<? extends Throwable> type) {
			super();
			this.argCount = argCount;
			this.type = type;
		}

		public int getArgCount() {
			return this.argCount;
		}

		public Class<? extends Throwable> getType() {
			return this.type;
		}

		public Object[] getArgs(Throwable t, Object[] args) {
			Object[] result = new Object[getArgCount()];
			int startArgs = 0;
			if (this.type != null) {
				result[0] = t;
				startArgs = 1;
			}
			int length = result.length - startArgs > args.length ? args.length : result.length - startArgs;
			if (length == 0) {
				return result;
			}
			System.arraycopy(args, 0, result, startArgs, length);
			return result;
		}

	}

}
