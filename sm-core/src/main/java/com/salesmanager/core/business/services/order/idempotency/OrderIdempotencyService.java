package com.salesmanager.core.business.services.order.idempotency;

import java.util.Date;

import com.salesmanager.core.model.order.idempotency.OrderIdempotencyKey;

/**
 * Stores checkout Idempotency-Keys. Every method commits in its own
 * transaction so the key is visible to concurrent retries immediately.
 */
public interface OrderIdempotencyService {

	/** Returns the stored record for this merchant and key, or null. */
	OrderIdempotencyKey findKey(Integer merchantId, String key);

	/**
	 * Inserts a new in-flight record. Throws a persistence exception (unique
	 * constraint) if another request claimed the key first.
	 */
	OrderIdempotencyKey claimKey(Integer merchantId, String key, String cartCode, String requestHash);

	/** Attaches the resulting order id to a claimed key. */
	void completeKey(Long recordId, Long orderId);

	/** Removes a claimed key after a failed checkout so the client may retry with the same key. */
	void releaseKey(Long recordId);

	/** Removes every key bound to the order so the client may retry the checkout. Returns the rows removed. */
	int releaseKeysForOrder(Long orderId);

	/**
	 * Deletes keys created before the cutoff, except keys whose order is still
	 * PAYMENT_PENDING (a retry must keep resolving to that order until it is
	 * reconciled). Returns the rows removed.
	 */
	int deleteExpiredKeys(Date createdBefore);
}
