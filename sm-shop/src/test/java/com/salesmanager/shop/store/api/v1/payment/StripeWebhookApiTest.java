package com.salesmanager.shop.store.api.v1.payment;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderReconciler;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.system.IntegrationConfiguration;

class StripeWebhookApiTest {

	private static final String SECRET = "whsec_test_secret";
	private static final String PAYLOAD =
			"{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_123\"}}}";

	private MockMvc mvc;
	private PaymentAttemptService attempts;
	private PendingOrderReconciler reconciler;

	@BeforeEach
	void setUp() throws Exception {
		MerchantStoreService stores = mock(MerchantStoreService.class);
		PaymentService payments = mock(PaymentService.class);
		attempts = mock(PaymentAttemptService.class);
		reconciler = mock(PendingOrderReconciler.class);

		MerchantStore store = new MerchantStore();
		store.setId(1);
		store.setCode("DEFAULT");
		when(stores.getByCode("DEFAULT")).thenReturn(store);
		IntegrationConfiguration config = new IntegrationConfiguration();
		config.setIntegrationKeys(Collections.singletonMap("webhookSecret", SECRET));
		when(payments.getPaymentConfiguration("stripe3", store)).thenReturn(config);
		when(reconciler.reconcileOrder(anyLong())).thenReturn(new PendingOrderReconciler.Result());

		StripeWebhookApi api = new StripeWebhookApi();
		ReflectionTestUtils.setField(api, "merchantStoreService", stores);
		ReflectionTestUtils.setField(api, "paymentService", payments);
		ReflectionTestUtils.setField(api, "paymentAttemptService", attempts);
		ReflectionTestUtils.setField(api, "reconciler", reconciler);
		mvc = MockMvcBuilders.standaloneSetup(api).build();
	}

	private static String sign(String payload, String secret) throws Exception {
		long ts = System.currentTimeMillis() / 1000;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] sig = mac.doFinal((ts + "." + payload).getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder();
		for (byte b : sig) {
			hex.append(String.format("%02x", b));
		}
		return "t=" + ts + ",v1=" + hex;
	}

	private PaymentAttempt attempt(int merchantId) {
		PaymentAttempt a = new PaymentAttempt();
		a.setOrderId(42L);
		a.setMerchantId(merchantId);
		return a;
	}

	@Test
	void validSignatureReconcilesTheOrder() throws Exception {
		when(attempts.findByGatewayRef("pi_123")).thenReturn(attempt(1));
		mvc.perform(post("/api/v1/webhooks/stripe/DEFAULT").contentType(MediaType.APPLICATION_JSON)
				.header("Stripe-Signature", sign(PAYLOAD, SECRET)).content(PAYLOAD))
				.andExpect(status().isOk());
		verify(reconciler).reconcileOrder(42L);
	}

	@Test
	void badSignatureIsRejected() throws Exception {
		mvc.perform(post("/api/v1/webhooks/stripe/DEFAULT").contentType(MediaType.APPLICATION_JSON)
				.header("Stripe-Signature", sign(PAYLOAD, "whsec_wrong")).content(PAYLOAD))
				.andExpect(status().isBadRequest());
		verify(reconciler, never()).reconcileOrder(anyLong());
	}

	@Test
	void attemptOfAnotherStoreIsIgnored() throws Exception {
		when(attempts.findByGatewayRef("pi_123")).thenReturn(attempt(2));
		mvc.perform(post("/api/v1/webhooks/stripe/DEFAULT").contentType(MediaType.APPLICATION_JSON)
				.header("Stripe-Signature", sign(PAYLOAD, SECRET)).content(PAYLOAD))
				.andExpect(status().isOk());
		verify(reconciler, never()).reconcileOrder(anyLong());
	}

	@Test
	void unconfiguredStoreReturns404() throws Exception {
		mvc.perform(post("/api/v1/webhooks/stripe/OTHER").contentType(MediaType.APPLICATION_JSON)
				.header("Stripe-Signature", sign(PAYLOAD, SECRET)).content(PAYLOAD))
				.andExpect(status().isNotFound());
	}
}
