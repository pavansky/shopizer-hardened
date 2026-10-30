package com.salesmanager.shop.application.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncConfigTest {

	@Test
	public void asyncExecutorIsBoundedWithCallerRuns() {
		ThreadPoolTaskExecutor executor = new AsyncConfig().shopAsyncExecutor();
		executor.initialize();
		try {
			assertEquals(5, executor.getCorePoolSize());
			assertEquals(10, executor.getMaxPoolSize());
			assertEquals("sm-async-", executor.getThreadNamePrefix());
			assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler()
					instanceof ThreadPoolExecutor.CallerRunsPolicy);
		} finally {
			executor.shutdown();
		}
	}

	@Test
	public void asyncWorkRunsWithTheCallersRequestId() throws Exception {
		ThreadPoolTaskExecutor executor = new AsyncConfig().shopAsyncExecutor();
		executor.initialize();
		try {
			MDC.put("requestId", "req-async");
			String seen = executor.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS);
			assertEquals("req-async", seen);
		} finally {
			MDC.clear();
			executor.shutdown();
		}
	}
}
