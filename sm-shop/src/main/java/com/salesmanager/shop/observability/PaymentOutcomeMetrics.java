package com.salesmanager.shop.observability;

import java.util.Map;

import javax.annotation.Resource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.modules.integration.PaymentOutcomeUnknownException;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * shopizer_payment_outcomes_total{store, module, outcome}: one count per gateway charge
 * attempted at checkout, recorded around PaymentService.processPayment.
 *
 *   success  the module returned a transaction
 *   failed   a definitive refusal (decline, module not configured or inactive); no money moved
 *   unknown  the outcome is not known (timeout, IO error, unexpected error); the order stays
 *            PAYMENT_PENDING for the reconciler
 *
 * The split follows OrderServiceImpl.isOutcomeUnknown, which decides what happens to the
 * order. Every label is bounded: store comes from the resolved MerchantStore, module must be
 * a registered payment module (anything else is "other").
 */
@Aspect
@Component
public class PaymentOutcomeMetrics {

	static final String NAME = "shopizer.payment.outcomes";

	private final MeterRegistry registry;

	@Resource(name = "paymentModules")
	private Map<String, PaymentModule> paymentModules;

	public PaymentOutcomeMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	@Around("execution(* com.salesmanager.core.business.services.payments.PaymentService.processPayment(..))")
	public Object record(ProceedingJoinPoint call) throws Throwable {
		Object[] args = call.getArgs();
		String store = args.length > 1 && args[1] instanceof MerchantStore ? ((MerchantStore) args[1]).getCode() : null;
		String module = args.length > 2 && args[2] instanceof Payment ? ((Payment) args[2]).getModuleName() : null;
		Object result;
		try {
			result = call.proceed();
		} catch (Throwable e) {
			count(store, module, isOutcomeUnknown(e) ? "unknown" : "failed");
			throw e;
		}
		count(store, module, "success");
		return result;
	}

	private void count(String store, String module, String outcome) {
		Counter.builder(NAME)
				.description("Payment attempts at checkout by store, payment module and outcome")
				.tag("store", store == null ? "none" : store)
				.tag("module", module == null ? "none" : paymentModules != null && paymentModules.containsKey(module) ? module : "other")
				.tag("outcome", outcome)
				.register(registry).increment();
	}

	/** Same rule as OrderServiceImpl.isOutcomeUnknown: only a ServiceException is definitive. */
	static boolean isOutcomeUnknown(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof PaymentOutcomeUnknownException || t instanceof java.io.IOException
					|| t instanceof java.util.concurrent.TimeoutException) {
				return true;
			}
		}
		return !(e instanceof ServiceException);
	}

	void setPaymentModules(Map<String, PaymentModule> paymentModules) {
		this.paymentModules = paymentModules;
	}
}
