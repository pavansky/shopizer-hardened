package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.stripe.Stripe;
import com.stripe.net.BearerTokenAuthenticator;
import com.stripe.net.RequestOptions;

public class StripeRequestOptionsTest {

	@Test
	public void eachStoreGetsItsOwnKeyAndGlobalIsNeverSet() {
		Stripe3Payment module = new Stripe3Payment();
		RequestOptions storeA = module.requestOptions("sk_test_storeA");
		RequestOptions storeB = module.requestOptions("sk_test_storeB");

		assertNotSame(storeA, storeB);
		assertEquals("sk_test_storeA", apiKey(storeA));
		assertEquals("sk_test_storeB", apiKey(storeB));
		assertEquals(Integer.valueOf(2), storeA.getMaxNetworkRetries());
		assertNull(Stripe.apiKey);
	}

	@Test
	public void legacyModuleUsesPerCallKey() {
		RequestOptions options = new StripePayment().requestOptions("sk_test_storeC");

		assertEquals("sk_test_storeC", apiKey(options));
		assertEquals(Integer.valueOf(2), options.getMaxNetworkRetries());
		assertNull(Stripe.apiKey);
	}

	@Test
	public void bothModulesSetExplicitTimeoutsInsteadOfTheSdkDefaults() {
		// stripe-java defaults to 30 s connect and 80 s read; a checkout must not wait that long
		for (RequestOptions options : new RequestOptions[] { new Stripe3Payment().requestOptions("sk", "ref-1"),
				new StripePayment().requestOptions("sk", "ref-1") }) {
			assertEquals(Integer.valueOf(3000), options.getConnectTimeout());
			assertEquals(Integer.valueOf(15000), options.getReadTimeout());
			assertEquals(Integer.valueOf(2), options.getMaxNetworkRetries());
			// retries reuse this key, so a retried capture or charge is applied once
			assertEquals("ref-1", options.getIdempotencyKey());
		}
	}

	@Test
	public void timeoutsComeFromProperties() {
		Stripe3Payment module = new Stripe3Payment();
		ReflectionTestUtils.setField(module, "connectTimeoutMs", 1000);
		ReflectionTestUtils.setField(module, "readTimeoutMs", 5000);
		ReflectionTestUtils.setField(module, "maxNetworkRetries", 0);

		RequestOptions options = module.requestOptions("sk");

		assertEquals(Integer.valueOf(1000), options.getConnectTimeout());
		assertEquals(Integer.valueOf(5000), options.getReadTimeout());
		assertEquals(Integer.valueOf(0), options.getMaxNetworkRetries());
	}

	/** The SDK keeps the per-call key inside a bearer-token authenticator. */
	private static String apiKey(RequestOptions options) {
		return ((BearerTokenAuthenticator) options.getAuthenticator()).getApiKey();
	}
}
