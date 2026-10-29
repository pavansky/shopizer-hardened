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

	/** A charge the shop never recorded was confirmed by the gateway and the order finalised. */
	default void confirmedByGateway() {
	}

	/** An order with an unconfirmed payment was moved to PAYMENT_REVIEW. */
	default void review() {
	}

	/** The gateway could not be asked about an attempt; the order was left as it was. */
	default void lookupError() {
	}

	/** A late charge on a failed or cancelled order was given back. */
	default void compensated() {
	}

	/** A late charge on a failed or cancelled order could not be given back automatically. */
	default void compensationNeeded() {
	}
}
