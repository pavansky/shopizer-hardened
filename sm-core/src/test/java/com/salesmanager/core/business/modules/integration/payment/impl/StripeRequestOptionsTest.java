package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.stripe.Stripe;
import com.stripe.net.BearerTokenAuthenticator;
import com.stripe.net.RequestOptions;

public class StripeRequestOptionsTest {

	@Test
	public void eachStoreGetsItsOwnKeyAndGlobalIsNeverSet() {
		RequestOptions storeA = Stripe3Payment.requestOptions("sk_test_storeA");
		RequestOptions storeB = Stripe3Payment.requestOptions("sk_test_storeB");

		assertNotSame(storeA, storeB);
		assertEquals("sk_test_storeA", apiKey(storeA));
		assertEquals("sk_test_storeB", apiKey(storeB));
		assertEquals(Integer.valueOf(2), storeA.getMaxNetworkRetries());
		assertNull(Stripe.apiKey);
	}

	@Test
	public void legacyModuleUsesPerCallKey() {
		RequestOptions options = StripePayment.requestOptions("sk_test_storeC");

		assertEquals("sk_test_storeC", apiKey(options));
		assertEquals(Integer.valueOf(2), options.getMaxNetworkRetries());
		// unset per call: the SDK default read timeout applies
		assertNull(options.getReadTimeout());
		assertNull(Stripe.apiKey);
	}

	/** The SDK keeps the per-call key inside a bearer-token authenticator. */
	private static String apiKey(RequestOptions options) {
		return ((BearerTokenAuthenticator) options.getAuthenticator()).getApiKey();
	}
}
