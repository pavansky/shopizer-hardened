package com.salesmanager.core.modules.integration.payment.model;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;

/**
 * Optional capability of a payment module: give back money the gateway took
 * for an order the shop has already failed or cancelled. Voids when the
 * payment is not settled yet, refunds it in full otherwise.
 */
public interface PaymentCompensation {

	/**
	 * @param status the SUCCEEDED answer of {@link PaymentStatusLookup#lookup}
	 * @return the REFUND transaction to record against the order
	 */
	Transaction compensate(MerchantStore store, IntegrationConfiguration configuration, PaymentAttempt attempt,
			GatewayPaymentStatus status) throws IntegrationException;
}
