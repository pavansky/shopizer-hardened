package com.salesmanager.core.modules.integration.payment.model;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;

/**
 * Optional capability of a payment module: ask the gateway what happened to a
 * payment attempt. Used by the reconciler when the shop's own records do not
 * say whether money was taken.
 */
public interface PaymentStatusLookup {

	/**
	 * @throws IntegrationException or a runtime exception when the gateway cannot be reached;
	 *         the caller then leaves the order as it is
	 */
	GatewayPaymentStatus lookup(MerchantStore store, IntegrationConfiguration configuration, PaymentAttempt attempt)
			throws IntegrationException;
}
