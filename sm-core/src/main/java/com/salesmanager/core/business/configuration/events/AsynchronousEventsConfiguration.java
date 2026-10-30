package com.salesmanager.core.business.configuration.events;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.salesmanager.core.business.configuration.logging.MdcTaskDecorator;

/**
 * Events will be asynchronous (in a different thread)
 * @author carlsamson
 *
 */
@Configuration
public class AsynchronousEventsConfiguration {

	/*
	 * The multicaster used to run on SimpleAsyncTaskExecutor, which starts a new,
	 * unbounded thread for every published event. Under load (bulk imports, order
	 * bursts) that can exhaust native threads. A bounded pool with a queue is used
	 * instead; when both are full, CallerRunsPolicy runs the listener on the
	 * publishing thread, which applies back pressure rather than dropping events.
	 */
	@Value("${config.events.executor.corePoolSize:4}")
	private int corePoolSize = 4;

	@Value("${config.events.executor.maxPoolSize:16}")
	private int maxPoolSize = 16;

	@Value("${config.events.executor.queueCapacity:500}")
	private int queueCapacity = 500;

	@Value("${config.events.executor.awaitTerminationSeconds:30}")
	private int awaitTerminationSeconds = 30;

	@Bean(name = "applicationEventExecutor")
	public ThreadPoolTaskExecutor applicationEventExecutor() {
		return buildExecutor(corePoolSize, maxPoolSize, queueCapacity, awaitTerminationSeconds);
	}

	@Bean(name = "applicationEventMulticaster")
	public ApplicationEventMulticaster simpleApplicationEventMulticaster(
			ThreadPoolTaskExecutor applicationEventExecutor) {
		SimpleApplicationEventMulticaster eventMulticaster = new SimpleApplicationEventMulticaster();
		eventMulticaster.setTaskExecutor(applicationEventExecutor);
		return eventMulticaster;
	}

	static ThreadPoolTaskExecutor buildExecutor(int core, int max, int queue, int awaitSeconds) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(core);
		executor.setMaxPoolSize(Math.max(core, max));
		executor.setQueueCapacity(queue);
		executor.setThreadNamePrefix("sm-event-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		// let in-flight listeners (e.g. order confirmation emails) finish on shutdown
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(awaitSeconds);
		// listeners log with the request id of the request that published the event
		executor.setTaskDecorator(new MdcTaskDecorator());
		// initialization is left to Spring (ThreadPoolTaskExecutor is an InitializingBean)
		return executor;
	}

}
