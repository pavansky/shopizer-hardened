package com.salesmanager.core.business.modules.integration.payment.impl;

import com.salesmanager.core.modules.integration.IntegrationException;

/**
 * The PaymentIntent is not paid yet: the customer has to act in the browser
 * before it can be captured. requires_action means the issuer asked for 3-D
 * Secure; requires_payment_method means the card was refused at confirmation
 * and another one is needed; requires_confirmation means the client never
 * confirmed it. No money moved. Checkout keeps the order PAYMENT_PENDING with
 * its stock and the attempt open, and the API answers 409 payment_action_required
 * with the client secret so the client can finish and retry with the same
 * Idempotency-Key.
 */
public class PaymentActionRequiredException extends IntegrationException {

	private static final long serialVersionUID = 1L;

	private final String paymentIntentId;
	private final String clientSecret;
	private final String status;
	private Long orderId;

	public PaymentActionRequiredException(String paymentIntentId, String clientSecret, String status) {
		super("Stripe PaymentIntent " + paymentIntentId + " is " + status + ": the customer must complete the payment");
		setExceptionType(TRANSACTION_EXCEPTION);
		setErrorCode(TRANSACTION_EXCEPTION);
		setMessageCode("message.payment.error");
		this.paymentIntentId = paymentIntentId;
		this.clientSecret = clientSecret;
		this.status = status;
	}

	/** PaymentIntent statuses meaning "not paid yet, the customer can still complete it". */
	public static boolean isCustomerActionStatus(String status) {
		return "requires_action".equals(status) || "requires_payment_method".equals(status)
				|| "requires_confirmation".equals(status);
	}

	/** The exception in the cause chain, or null. */
	public static PaymentActionRequiredException in(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof PaymentActionRequiredException) {
				return (PaymentActionRequiredException) t;
			}
		}
		return null;
	}

	public String getPaymentIntentId() {
		return paymentIntentId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public String getStatus() {
		return status;
	}

	/** The order kept open for the customer, set by checkout. */
	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}
}
