package com.salesmanager.shop.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.salesmanager.core.business.services.order.reconciliation.ReconciliationMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the pending-order reconciler's counts as Micrometer counters, visible at
 * /actuator/prometheus as shopizer_reconciliation_orders_total{outcome=...} and
 * shopizer_idempotency_keys_deleted_total. Alert on outcome="failed" or "error".
 */
@Configuration
public class ReconciliationMetricsConfig {

	@Bean
	public ReconciliationMetrics reconciliationMetrics(MeterRegistry registry) {
		Counter finalised = outcome(registry, "finalised");
		Counter failed = outcome(registry, "failed");
		Counter skipped = outcome(registry, "skipped");
		Counter error = outcome(registry, "error");
		Counter keys = Counter.builder("shopizer.idempotency.keys.deleted")
				.description("Expired idempotency keys removed").register(registry);
		return new ReconciliationMetrics() {
			public void finalised() { finalised.increment(); }
			public void failed() { failed.increment(); }
			public void skipped() { skipped.increment(); }
			public void error() { error.increment(); }
			public void keysDeleted(int count) { keys.increment(count); }
		};
	}

	private static Counter outcome(MeterRegistry registry, String outcome) {
		return Counter.builder("shopizer.reconciliation.orders")
				.description("PAYMENT_PENDING orders settled by the reconciler")
				.tag("outcome", outcome).register(registry);
	}
}
