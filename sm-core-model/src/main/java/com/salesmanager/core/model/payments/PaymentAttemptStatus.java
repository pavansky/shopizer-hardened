package com.salesmanager.core.model.payments;

/**
 * What the shop knows about one call to a payment gateway.
 */
public enum PaymentAttemptStatus {

	/** Written before the gateway call. The call may or may not have reached the gateway. */
	INITIATED,
	/** The gateway confirmed the payment. */
	SUCCEEDED,
	/** The gateway declined, or nothing was charged. */
	FAILED,
	/** The call failed in a way that does not tell whether money was taken (timeout, IO error). */
	UNKNOWN
}
