package com.salesmanager.core.business.services.order.idempotency;

/**
 * Raised when an Idempotency-Key is reused for a different cart or request
 * body, or while the original request is still in flight. Maps to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public IdempotencyConflictException(String message) {
		super(message);
	}
}
