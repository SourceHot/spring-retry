package org.springframework.retry.policy;


import java.net.ContentHandler;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.CircuitBreakerRetryPolicy.CircuitBreakerRetryContext;
import org.springframework.retry.support.RetryTemplate;

public class CircuitBreakerRetryPolicyTest  {

  public static void main(String[] args) throws Throwable {
    CircuitBreakerRetryPolicy circuitBreakerRetryPolicy = new CircuitBreakerRetryPolicy();



    RetryTemplate template = new RetryTemplate();
    template.setRetryPolicy(circuitBreakerRetryPolicy);

    template.execute(new RetryCallback<Object, Throwable>() {
      @Override
      public Object doWithRetry(RetryContext context) throws Throwable {
        Thread.sleep(3000);
        throw new RuntimeException("11");
//        return "aaa";
      }
    });
  }

}
