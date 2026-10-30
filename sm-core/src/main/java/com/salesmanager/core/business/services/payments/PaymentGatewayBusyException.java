package com.salesmanager.core.business.services.payments;

import com.salesmanager.core.business.exception.ServiceException;

/**
 * The per-gateway concurrency limit is reached. Thrown before the payment
 * module is called, so nothing was sent to the gateway and no money moved:
 * the client may retry after {@link #getRetryAfterSeconds()} seconds. The API
 * answers 503 Service Unavailable with a Retry-After header.
 */
public class PaymentGatewayBusyException extends ServiceException {

	private static final long serialVersionUID = 1L;

	private final String moduleCode;
	private final int retryAfterSeconds;

	public PaymentGatewayBusyException(String moduleCode, int retryAfterSeconds) {
		super(EXCEPTION_ERROR, "Payment gateway " + moduleCode
				+ " is busy; nothing was charged, retry in " + retryAfterSeconds + " s", "message.payment.error");
		this.moduleCode = moduleCode;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public String getModuleCode() {
		return moduleCode;
	}

	public int getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
