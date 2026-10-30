package com.salesmanager.shop.store.api.v1.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderReconciler;
import com.salesmanager.core.business.modules.integration.payment.impl.Stripe3Payment;
import com.salesmanager.core.business.services.payments.GatewayEventRecorder;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.system.IntegrationConfiguration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StripeWebhookApiTest {

	private static final String SECRET = "whsec_test_secret";
	private static final String PAYLOAD =
			"{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_123\"}}}";

	private MockMvc mvc;
	private PaymentAttemptService attempts;
	private PendingOrderReconciler reconciler;
	private GatewayEventRecorder recorder;
	private Stripe3Payment stripe;
	private PaymentService payments;
	private SimpleMeterRegistry meters;

	@BeforeEach
	void setUp() throws Exception {
		MerchantStoreService stores = mock(MerchantStoreService.class);
		payments = mock(PaymentService.class);
		recorder = mock(GatewayEventRecorder.class);
		stripe = mock(Stripe3Payment.class);
		meters = new SimpleMeterRegistry();
		when(payments.getPaymentModule("stripe3")).thenReturn(stripe);
		when(recorder.recordRefunds(anyLong(), any(), anyString())).thenReturn(new GatewayEventRecorder.RefundResult());
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
		ReflectionTestUtils.setField(api, "gatewayEventRecorder", recorder);
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("meterRegistry", meters);
		ReflectionTestUtils.setField(api, "meterRegistry", beans.getBeanProvider(MeterRegistry.class));
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

	private void deliver(String payload) throws Exception {
		mvc.perform(post("/api/v1/webhooks/stripe/DEFAULT").contentType(MediaType.APPLICATION_JSON)
				.header("Stripe-Signature", sign(payload, SECRET)).content(payload))
				.andExpect(status().isOk());
	}

	@Test
	void dashboardRefundIsAskedFromStripeAndRecorded() throws Exception {
		PaymentAttempt attempt = attempt(1);
		when(attempts.findByGatewayRef("pi_123")).thenReturn(attempt);
		List<Transaction> fromStripe = Collections.singletonList(new Transaction());
		when(stripe.succeededRefunds(any(), eq("ch_9"))).thenReturn(fromStripe);
		GatewayEventRecorder.RefundResult result = new GatewayEventRecorder.RefundResult();
		result.recorded = 1;
		when(recorder.recordRefunds(42L, fromStripe, "Stripe")).thenReturn(result);

		// the body's amounts are not used: the refunds are listed from Stripe
		deliver("{\"id\":\"evt_2\",\"type\":\"charge.refunded\",\"data\":{\"object\":"
				+ "{\"id\":\"ch_9\",\"payment_intent\":\"pi_123\",\"amount_refunded\":999999}}}");

		verify(recorder).recordRefunds(42L, fromStripe, "Stripe");
		verify(reconciler, never()).reconcileOrder(anyLong());
		assertEquals(1.0, meters.counter("shop.payment.webhook.refunds", "outcome", "recorded").count());
	}

	@Test
	void disputeCreatedIsWrittenToTheOrderHistory() throws Exception {
		when(attempts.findByGatewayRef("pi_123")).thenReturn(attempt(1));
		when(recorder.recordDispute(eq(42L), eq("dp_1"), eq("opened"), anyString(), eq(true))).thenReturn(true);

		deliver("{\"id\":\"evt_3\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":"
				+ "{\"id\":\"dp_1\",\"charge\":\"ch_9\",\"payment_intent\":\"pi_123\",\"amount\":1234,"
				+ "\"currency\":\"usd\",\"reason\":\"fraudulent\",\"status\":\"needs_response\","
				+ "\"evidence_details\":{\"due_by\":1893456000}}}}");

		ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
		verify(recorder).recordDispute(eq(42L), eq("dp_1"), eq("opened"), comment.capture(), eq(true));
		assertTrue(comment.getValue().contains("fraudulent"));
		assertTrue(comment.getValue().contains("12.34 USD"));
		assertTrue(comment.getValue().contains("2030-01-01"));
		assertEquals(1.0, meters.counter("shop.payment.disputes", "event", "created").count());
	}

	@Test
	void disputeClosedOnALegacyChargeIsFoundByChargeId() throws Exception {
		when(attempts.findByGatewayRef("ch_legacy")).thenReturn(attempt(1));

		deliver("{\"id\":\"evt_4\",\"type\":\"charge.dispute.closed\",\"data\":{\"object\":"
				+ "{\"id\":\"dp_2\",\"charge\":\"ch_legacy\",\"status\":\"won\",\"reason\":\"general\"}}}");

		verify(recorder).recordDispute(eq(42L), eq("dp_2"), eq("closed"), anyString(), eq(false));
	}

	@Test
	void otherEventTypesAreIgnored() throws Exception {
		deliver("{\"id\":\"evt_5\",\"type\":\"customer.created\",\"data\":{\"object\":{\"id\":\"cus_1\"}}}");

		verify(recorder, never()).recordRefunds(anyLong(), any(), anyString());
		verify(recorder, never()).recordDispute(anyLong(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
		verify(reconciler, never()).reconcileOrder(anyLong());
	}
}
