package com.salesmanager.core.business.services.order;

/**
 * The gateway call ended without a clear answer (timeout, IO error, gateway
 * server error). Money may or may not have been taken. The order stays in
 * PAYMENT_PENDING with its stock reserved and the reconciler asks the gateway
 * later. Callers must not retry the checkout as a new charge; the API answers
 * 202 Accepted with the order id.
 */
public class PaymentConfirmationPendingException extends OrderChargedNotRecordedException {

	private static final long serialVersionUID = 1L;

	public PaymentConfirmationPendingException(Long orderId, Throwable cause) {
		super(orderId, cause);
	}

	@Override
	public String getMessage() {
		return "Payment for order " + getOrderId() + " is being confirmed";
	}
}
