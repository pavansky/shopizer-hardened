package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.stripe.Stripe;
import com.stripe.net.RequestOptions;

public class StripeRequestOptionsTest {

	@Test
	public void eachStoreGetsItsOwnKeyAndGlobalIsNeverSet() {
		RequestOptions storeA = Stripe3Payment.requestOptions("sk_test_storeA");
		RequestOptions storeB = Stripe3Payment.requestOptions("sk_test_storeB");

		assertNotSame(storeA, storeB);
		assertEquals("sk_test_storeA", storeA.getApiKey());
		assertEquals("sk_test_storeB", storeB.getApiKey());
		assertEquals(2, storeA.getMaxNetworkRetries());
		assertNull(Stripe.apiKey);
	}

	@Test
	public void legacyModuleUsesPerCallKey() {
		RequestOptions options = StripePayment.requestOptions("sk_test_storeC");

		assertEquals("sk_test_storeC", options.getApiKey());
		assertEquals(2, options.getMaxNetworkRetries());
		assertEquals(Stripe.getReadTimeout(), options.getReadTimeout());
		assertNull(Stripe.apiKey);
	}
}
