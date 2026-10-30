package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

class Stripe3InitTest {

	@Test
	void intentIsCreatedForTheGivenAmountWithManualCaptureAndNoRedirects() throws Exception {
		final PaymentIntentCreateParams[] sent = new PaymentIntentCreateParams[1];
		Stripe3Payment stripe = new Stripe3Payment() {
			@Override
			protected PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options) {
				sent[0] = params;
				PaymentIntent pi = new PaymentIntent();
				pi.setId("pi_new");
				pi.setStatus("requires_payment_method");
				pi.setClientSecret("pi_new_secret_abc");
				return pi;
			}
		};
		MerchantStore store = new MerchantStore();
		Currency usd = new Currency();
		usd.setCurrency(java.util.Currency.getInstance("USD"));
		store.setCurrency(usd);
		IntegrationConfiguration configuration = new IntegrationConfiguration();
		configuration.setIntegrationKeys(Collections.singletonMap("secretKey", "sk_test_x"));
		Payment payment = new Payment();
		payment.setPaymentMetaData(new HashMap<>(Collections.singletonMap(Stripe3Payment.META_CART_CODE, "cart-1")));

		Transaction init = stripe.initTransaction(store, null, new BigDecimal("40.50"), payment, configuration, null);

		assertNotNull(sent[0]);
		assertEquals(Long.valueOf(4050L), sent[0].getAmount());
		assertEquals("usd", sent[0].getCurrency().toLowerCase());
		assertEquals(PaymentIntentCreateParams.CaptureMethod.MANUAL, sent[0].getCaptureMethod());
		assertEquals(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER,
				sent[0].getAutomaticPaymentMethods().getAllowRedirects());
		assertEquals("cart-1", sent[0].getMetadata().get("cart_code"));
		assertEquals("pi_new", init.getTransactionDetails().get("TRANSACTIONID"));
		assertEquals("pi_new_secret_abc", init.getTransactionDetails().get("INTENTSECRET"));
		assertEquals(new BigDecimal("40.50"), init.getAmount());
	}
}
