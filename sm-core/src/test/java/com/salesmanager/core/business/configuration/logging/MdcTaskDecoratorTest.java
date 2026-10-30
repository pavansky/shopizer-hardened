package com.salesmanager.core.business.configuration.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;

public class MdcTaskDecoratorTest {

	private final MdcTaskDecorator decorator = new MdcTaskDecorator();

	@After
	public void clear() {
		MDC.clear();
	}

	@Test
	public void poolThreadSeesTheSubmittersContextAndIsCleanedAfterwards() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			MDC.put("requestId", "req-1");
			Runnable task = decorator.decorate(() -> assertEquals("req-1", MDC.get("requestId")));
			MDC.clear();

			Future<?> ran = pool.submit(task);
			ran.get(5, TimeUnit.SECONDS); // rethrows the assertion if it failed

			// the pool thread must not keep the request id for the next, unrelated task
			Future<String> after = pool.submit(() -> MDC.get("requestId"));
			assertNull(after.get(5, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void callerRunsKeepsTheCallersOwnContext() {
		MDC.put("requestId", "outer");
		Runnable task = decorator.decorate(() -> MDC.put("requestId", "changed-inside"));
		task.run(); // what CallerRunsPolicy does when the pool is full
		assertEquals("outer", MDC.get("requestId"));
	}

	@Test
	public void emptyContextIsPropagatedAsEmpty() throws Exception {
		MDC.clear();
		Runnable task = decorator.decorate(() -> assertNull(MDC.get("requestId")));
		MDC.put("requestId", "set-after-submit");
		task.run();
		assertEquals("set-after-submit", MDC.get("requestId"));
	}
}
