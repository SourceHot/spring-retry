package org.springframework.book;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

public class RetryTemplateExampleTest {
	@Test
	public void testRetryTemplate() throws Exception {
		RetryTemplate template = new RetryTemplate();
		TimeoutRetryPolicy policy = new TimeoutRetryPolicy();
		template.setRetryPolicy(policy);
		String result = template.execute(new RetryCallback<String, Exception>() {
			public String doWithRetry(RetryContext arg0) throws Exception {

				// 执行一个操作，抛出异常
				throw new RuntimeException("aa");
			}
		}, new RecoveryCallback<String>() {
			@Override
			public String recover(RetryContext context) throws Exception {
				Throwable lastThrowable = context.getLastThrowable();
				return lastThrowable.getMessage();
			}
		});
		System.out.println(result);
	}

	@Test
	public void testRt() throws Throwable {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
				RetryTemplateExampleConfiguration.class);

		RemoteService remoteService = ctx.getBean("remoteService", RemoteService.class);
		remoteService.call();
	}

	@Configuration
	@EnableRetry
	public static class RetryTemplateExampleConfiguration {
		@Bean
		public RetryTemplate retryTemplate() {
			RetryTemplate retryTemplate = new RetryTemplate();
			return retryTemplate;
		}

		@Bean
		public RemoteService remoteService() {
			return new RemoteService();
		}

	}

	public static class RemoteService {
		/**
		 * 调用方法
		 */
		@Retryable(value = RuntimeException.class, maxAttempts = 2)
		public void call() {
			System.out.println("正在执行");
			throw new RuntimeException("RPC调用异常");
		}

		/**
		 * recover 机制
		 * @param e 异常
		 */
		@Recover
		public void recover(RuntimeException e) {
			System.out.println(e.getMessage());
		}
	}
}
