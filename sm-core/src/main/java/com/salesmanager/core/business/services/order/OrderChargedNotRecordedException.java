package com.salesmanager.core.business.services.order;

import com.salesmanager.core.business.exception.ServiceException;

/**
 * The payment was captured but the order could not be moved out of
 * PAYMENT_PENDING. The order row exists and must be reconciled; callers must
 * not allow the checkout to be retried as a new charge.
 */
public class OrderChargedNotRecordedException extends ServiceException {

	private static final long serialVersionUID = 1L;

	private final Long orderId;

	public OrderChargedNotRecordedException(Long orderId, Throwable cause) {
		super("Order " + orderId + " was charged but not finalised", cause);
		setExceptionType(EXCEPTION_ERROR);
		this.orderId = orderId;
	}

	public Long getOrderId() {
		return orderId;
	}
}
