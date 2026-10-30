package com.salesmanager.core.business.configuration.logging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copies the submitting thread's MDC (request id and similar) into work run on a
 * pool thread, so log lines written by @Async methods and event listeners can be
 * joined to the request that caused them.
 *
 * The worker's previous MDC is restored afterwards rather than cleared: with
 * CallerRunsPolicy a saturated pool runs the task on the submitting thread itself,
 * and clearing there would wipe the request's own context.
 */
public class MdcTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		Map<String, String> submitted = MDC.getCopyOfContextMap();
		return () -> {
			Map<String, String> previous = MDC.getCopyOfContextMap();
			apply(submitted);
			try {
				runnable.run();
			} finally {
				apply(previous);
			}
		};
	}

	private static void apply(Map<String, String> context) {
		if (context == null) {
			MDC.clear();
		} else {
			MDC.setContextMap(context);
		}
	}
}
