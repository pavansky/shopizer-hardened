package com.salesmanager.core.business.services.order.reconciliation;

import org.springframework.stereotype.Component;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.PaymentCompensation;
import com.salesmanager.core.modules.integration.payment.model.PaymentStatusLookup;

/**
 * Resolves the payment module, store and configuration of an attempt and asks
 * the gateway about it. Kept apart from the reconciler so the reconciler's
 * decisions can be tested without any gateway.
 */
@Component
public class PaymentGateways {

	private final PaymentService paymentService;
	private final MerchantStoreService merchantStoreService;

	public PaymentGateways(PaymentService paymentService, MerchantStoreService merchantStoreService) {
		this.paymentService = paymentService;
		this.merchantStoreService = merchantStoreService;
	}

	/**
	 * The gateway's answer. A module without a lookup answers PENDING, so the
	 * order is never failed without a gateway answer.
	 *
	 * @throws Exception when the gateway or the configuration cannot be reached
	 */
	public GatewayPaymentStatus lookup(PaymentAttempt attempt) throws Exception {
		PaymentModule module = paymentService.getPaymentModule(attempt.getModuleCode());
		if (!(module instanceof PaymentStatusLookup)) {
			return GatewayPaymentStatus.pending(null, "module " + attempt.getModuleCode() + " has no status lookup");
		}
		MerchantStore store = store(attempt);
		return ((PaymentStatusLookup) module).lookup(store, configuration(attempt, store), attempt);
	}

	/**
	 * Gives back a late charge.
	 *
	 * @return the REFUND transaction, or null when the module cannot compensate
	 */
	public Transaction compensate(PaymentAttempt attempt, GatewayPaymentStatus status) throws Exception {
		PaymentModule module = paymentService.getPaymentModule(attempt.getModuleCode());
		if (!(module instanceof PaymentCompensation)) {
			return null;
		}
		MerchantStore store = store(attempt);
		return ((PaymentCompensation) module).compensate(store, configuration(attempt, store), attempt, status);
	}

	private MerchantStore store(PaymentAttempt attempt) throws ServiceException {
		MerchantStore store = merchantStoreService.getById(attempt.getMerchantId());
		if (store == null) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR,
					"Store " + attempt.getMerchantId() + " of payment attempt " + attempt.getAttemptRef() + " not found");
		}
		return store;
	}

	private IntegrationConfiguration configuration(PaymentAttempt attempt, MerchantStore store)
			throws ServiceException {
		IntegrationConfiguration configuration = paymentService.getPaymentConfiguration(attempt.getModuleCode(), store);
		if (configuration == null) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR,
					"Payment module " + attempt.getModuleCode() + " is not configured for store " + store.getId());
		}
		return configuration;
	}
}
