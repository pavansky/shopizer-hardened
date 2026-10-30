package com.salesmanager.core.business.modules.integration.payment.impl;

import com.salesmanager.core.business.exception.ServiceException;

/**
 * The PaymentIntent of this checkout is already held by an order of another cart
 * that waits for its customer (3-D Secure). The checkout is refused before anything
 * is reserved or charged, and the waiting order is left as it is. The API answers
 * 409 payment_intent_in_use.
 */
public class PaymentIntentInUseException extends ServiceException {

	private static final long serialVersionUID = 1L;

	private final String paymentIntentId;

	public PaymentIntentInUseException(String paymentIntentId) {
		super(EXCEPTION_ERROR, "PaymentIntent " + paymentIntentId
				+ " belongs to another checkout; start the payment again", "message.payment.error");
		this.paymentIntentId = paymentIntentId;
	}

	public String getPaymentIntentId() {
		return paymentIntentId;
	}
}
