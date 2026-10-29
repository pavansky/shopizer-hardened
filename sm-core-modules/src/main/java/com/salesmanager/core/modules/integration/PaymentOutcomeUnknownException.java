package com.salesmanager.core.modules.integration;

/**
 * The gateway call failed in a way that does not tell whether money was taken:
 * a timeout, a dropped connection or a gateway server error. The checkout must
 * not treat it as a decline; the outcome is settled later by asking the
 * gateway.
 */
public class PaymentOutcomeUnknownException extends IntegrationException {

	private static final long serialVersionUID = 1L;

	public PaymentOutcomeUnknownException(String message, Exception cause) {
		super(message, cause);
		setExceptionType(TRANSACTION_EXCEPTION);
		setErrorCode(TRANSACTION_EXCEPTION);
		setMessageCode("message.payment.error");
	}
}
