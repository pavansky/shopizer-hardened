package com.salesmanager.core.business.configuration.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsynchronousEventsConfigurationTest {

	@Test
	public void executorIsBoundedAndRunsOnCallerWhenSaturated() throws Exception {
		ThreadPoolTaskExecutor executor = AsynchronousEventsConfiguration.buildExecutor(1, 1, 1, 5);
		executor.initialize();
		try {
			assertEquals(1, executor.getMaxPoolSize());
			assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler()
					instanceof ThreadPoolExecutor.CallerRunsPolicy);

			CountDownLatch release = new CountDownLatch(1);
			executor.execute(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
			executor.execute(() -> { }); // fills the queue

			// pool and queue are full: the third task must run on this thread
			String[] ranOn = new String[1];
			executor.execute(() -> ranOn[0] = Thread.currentThread().getName());
			assertEquals(Thread.currentThread().getName(), ranOn[0]);
			release.countDown();
		} finally {
			executor.shutdown();
		}
	}
}
