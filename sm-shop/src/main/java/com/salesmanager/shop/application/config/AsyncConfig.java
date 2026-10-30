package com.salesmanager.shop.application.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.salesmanager.core.business.configuration.logging.MdcTaskDecorator;

@Configuration
@EnableScheduling
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

  /*
   * Previously a raw Executors.newFixedThreadPool(5): unbounded queue, anonymous
   * thread names and never shut down by the container, so pending @Async work was
   * lost on redeploy. It is now a Spring-managed ThreadPoolTaskExecutor with a
   * bounded queue, CallerRunsPolicy back pressure and graceful shutdown.
   */
  @Value("${config.async.executor.corePoolSize:5}")
  private int corePoolSize = 5;

  @Value("${config.async.executor.maxPoolSize:10}")
  private int maxPoolSize = 10;

  @Value("${config.async.executor.queueCapacity:200}")
  private int queueCapacity = 200;

  @Value("${config.async.executor.awaitTerminationSeconds:30}")
  private int awaitTerminationSeconds = 30;

  @Bean(name = "shopAsyncExecutor")
  public ThreadPoolTaskExecutor shopAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(Math.max(corePoolSize, maxPoolSize));
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("sm-async-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
    // @Async work (order emails) logs with the request id of the request that started it
    executor.setTaskDecorator(new MdcTaskDecorator());
    return executor;
  }

  @Override
  public Executor getAsyncExecutor() {
    // returns the container-managed bean (the @Configuration proxy resolves the singleton)
    return shopAsyncExecutor();
  }

  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return new SimpleAsyncUncaughtExceptionHandler();
  }
}
