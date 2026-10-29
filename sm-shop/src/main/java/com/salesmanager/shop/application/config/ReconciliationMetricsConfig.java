package com.salesmanager.shop.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.salesmanager.core.business.services.order.reconciliation.ReconciliationMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the pending-order reconciler's counts as Micrometer counters, visible at
 * /actuator/prometheus as shopizer_reconciliation_orders_total{outcome=...} and
 * shopizer_idempotency_keys_deleted_total. Alert on outcome="error", "review", "lookup_error"
 * and above all "compensation_needed": that one means a customer was charged for an order
 * the shop failed and the money has to be returned by hand.
 */
@Configuration
public class ReconciliationMetricsConfig {

	@Bean
	public ReconciliationMetrics reconciliationMetrics(MeterRegistry registry) {
		Counter finalised = outcome(registry, "finalised");
		Counter failed = outcome(registry, "failed");
		Counter skipped = outcome(registry, "skipped");
		Counter error = outcome(registry, "error");
		Counter confirmed = outcome(registry, "confirmed_by_gateway");
		Counter review = outcome(registry, "review");
		Counter lookupError = outcome(registry, "lookup_error");
		Counter compensated = outcome(registry, "compensated");
		Counter compensationNeeded = outcome(registry, "compensation_needed");
		Counter keys = Counter.builder("shopizer.idempotency.keys.deleted")
				.description("Expired idempotency keys removed").register(registry);
		return new ReconciliationMetrics() {
			public void finalised() { finalised.increment(); }
			public void failed() { failed.increment(); }
			public void skipped() { skipped.increment(); }
			public void error() { error.increment(); }
			public void keysDeleted(int count) { keys.increment(count); }
			public void confirmedByGateway() { confirmed.increment(); }
			public void review() { review.increment(); }
			public void lookupError() { lookupError.increment(); }
			public void compensated() { compensated.increment(); }
			public void compensationNeeded() { compensationNeeded.increment(); }
		};
	}

	private static Counter outcome(MeterRegistry registry, String outcome) {
		return Counter.builder("shopizer.reconciliation.orders")
				.description("PAYMENT_PENDING orders settled by the reconciler")
				.tag("outcome", outcome).register(registry);
	}
}
