package com.salesmanager.core.business.modules.integration.payment.sandbox;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.system.IntegrationConfiguration;

/**
 * Shared set-up for the sandbox integration tests. Keys are read from the
 * environment only and are never printed.
 */
final class SandboxFixtures {

	static final String STRIPE_KEY_VAR = "STRIPE_TEST_SECRET_KEY";
	static final String BT_MERCHANT_VAR = "BRAINTREE_SANDBOX_MERCHANT_ID";
	static final String BT_PUBLIC_VAR = "BRAINTREE_SANDBOX_PUBLIC_KEY";
	static final String BT_PRIVATE_VAR = "BRAINTREE_SANDBOX_PRIVATE_KEY";

	private SandboxFixtures() {
	}

	/** The Stripe key, refusing anything that is not a test-mode secret key. */
	static String stripeTestKey() {
		String key = System.getenv(STRIPE_KEY_VAR);
		if (key == null || !key.startsWith("sk_test_")) {
			throw new IllegalStateException(STRIPE_KEY_VAR + " is not a Stripe test-mode secret key; refusing to run");
		}
		return key;
	}

	static IntegrationConfiguration stripeConfiguration() {
		IntegrationConfiguration configuration = new IntegrationConfiguration();
		Map<String, String> keys = new HashMap<>();
		keys.put("secretKey", stripeTestKey());
		keys.put("publishableKey", "pk_test_unused");
		configuration.setIntegrationKeys(keys);
		configuration.setEnvironment("TEST");
		return configuration;
	}

	static IntegrationConfiguration braintreeConfiguration() {
		IntegrationConfiguration configuration = new IntegrationConfiguration();
		Map<String, String> keys = new HashMap<>();
		keys.put("merchant_id", System.getenv(BT_MERCHANT_VAR));
		keys.put("public_key", System.getenv(BT_PUBLIC_VAR));
		keys.put("private_key", System.getenv(BT_PRIVATE_VAR));
		configuration.setIntegrationKeys(keys);
		configuration.setEnvironment("TEST");
		return configuration;
	}

	static MerchantStore store() {
		MerchantStore store = new MerchantStore();
		store.setStorename("Sandbox proof store");
		Currency usd = new Currency();
		usd.setCurrency(java.util.Currency.getInstance("USD"));
		store.setCurrency(usd);
		return store;
	}

	static String newAttemptRef() {
		return UUID.randomUUID().toString();
	}

	static PaymentAttempt attempt(String attemptRef, String gatewayRef, BigDecimal amount) {
		PaymentAttempt attempt = new PaymentAttempt();
		attempt.setAttemptRef(attemptRef);
		attempt.setGatewayRef(gatewayRef);
		attempt.setAmount(amount);
		attempt.setCurrency("USD");
		attempt.setDateCreated(new Date());
		return attempt;
	}

	static Payment payment(String tokenKey, String token, String attemptRef, BigDecimal amount) {
		Payment payment = new Payment();
		Map<String, String> meta = new HashMap<>();
		meta.put(tokenKey, token);
		meta.put(PaymentAttempt.META_ATTEMPT_REF, attemptRef);
		payment.setPaymentMetaData(meta);
		payment.setAmount(amount);
		return payment;
	}

	/** One proof line per check; ids, statuses and amounts only. */
	static void proof(String gateway, String check, String detail) {
		System.out.println("[sandbox-proof] " + gateway + " | " + check + " | " + detail);
	}
}
