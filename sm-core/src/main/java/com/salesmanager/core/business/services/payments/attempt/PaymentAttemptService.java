package com.salesmanager.core.business.services.payments.attempt;

import java.util.Date;
import java.util.List;

import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;

/**
 * Reads and updates payment attempts. Every write commits on its own
 * (REQUIRES_NEW) so the record survives whatever happens to the caller.
 */
public interface PaymentAttemptService {

	/** Inserts the attempt in INITIATED and commits it. */
	PaymentAttempt begin(PaymentAttempt attempt);

	/** Moves the attempt to the given status and commits. Returns false when the attempt does not exist. */
	boolean update(Long attemptId, PaymentAttemptStatus status, String gatewayRef, String reason);

	/** Most recent attempt of the order, or null. */
	PaymentAttempt latestForOrder(Long orderId);

	/** The attempt that sent this gateway reference (for example a Stripe PaymentIntent id), or null. */
	PaymentAttempt findByGatewayRef(String gatewayRef);

	/** True when any attempt of the order is SUCCEEDED. */
	boolean hasSucceeded(Long orderId);

	/**
	 * Attempts still INITIATED or UNKNOWN, created before the cutoff, whose order
	 * is already PAYMENT_FAILED or CANCELED. Ascending by id, after the given id.
	 */
	List<PaymentAttempt> findOrphans(Date createdBefore, Long afterId, int limit);
}
