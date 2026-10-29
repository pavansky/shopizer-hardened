package com.salesmanager.core.business.services.order.reconciliation;

/**
 * Counters for the reconciliation jobs. sm-core has no Micrometer dependency,
 * so the reconciler looks this bean up optionally; the web module can provide
 * an implementation backed by its MeterRegistry. Without one the counts are
 * only logged.
 */
public interface ReconciliationMetrics {

	void finalised();

	void failed();

	void skipped();

	void error();

	void keysDeleted(int count);
}
