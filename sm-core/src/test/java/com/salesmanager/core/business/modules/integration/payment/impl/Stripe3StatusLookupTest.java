package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus.Outcome;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;

class Stripe3StatusLookupTest {

	private final IntegrationConfiguration configuration = new IntegrationConfiguration();
	private final PaymentAttempt attempt = new PaymentAttempt();

	Stripe3StatusLookupTest() {
		configuration.setIntegrationKeys(Collections.singletonMap("secretKey", "sk_test_x"));
		attempt.setAttemptRef("11111111-2222-3333-4444-555555555555");
		attempt.setGatewayRef("pi_123");
		attempt.setCurrency("USD");
		attempt.setAmount(new BigDecimal("12.34"));
	}

	private static PaymentIntent intent(String status) {
		PaymentIntent pi = new PaymentIntent();
		pi.setId("pi_123");
		pi.setStatus(status);
		pi.setCurrency("usd");
		pi.setAmount(1234L);
		pi.setAmountReceived("succeeded".equals(status) ? 1234L : 0L);
		pi.setAmountCapturable("requires_capture".equals(status) ? 1234L : 0L);
		return pi;
	}

	private static Stripe3Payment returning(PaymentIntent pi) {
		return new Stripe3Payment() {
			@Override
			protected PaymentIntent retrievePaymentIntent(String id, RequestOptions options) {
				assertEquals("pi_123", id);
				return pi;
			}
		};
	}

	private Outcome map(String status) throws Exception {
		return returning(intent(status)).lookup(null, configuration, attempt).getOutcome();
	}

	@Test
	void statusMapping() throws Exception {
		assertEquals(Outcome.SUCCEEDED, map("succeeded"));
		assertEquals(Outcome.SUCCEEDED, map("requires_capture"));
		assertEquals(Outcome.PENDING, map("processing"));
		assertEquals(Outcome.FAILED, map("canceled"));
		assertEquals(Outcome.FAILED, map("requires_payment_method"));
		assertEquals(Outcome.PENDING, map("requires_action"));
	}

	@Test
	void succeededCarriesAmountAndId() throws Exception {
		GatewayPaymentStatus s = returning(intent("succeeded")).lookup(null, configuration, attempt);
		assertTrue(s.isCaptured());
		assertEquals("pi_123", s.getGatewayTransactionId());
		assertEquals(new BigDecimal("12.34"), s.getAmount());
		assertFalse(returning(intent("requires_capture")).lookup(null, configuration, attempt).isCaptured());
	}

	@Test
	void noGatewayRefMeansNotFound() throws Exception {
		attempt.setGatewayRef(null);
		assertEquals(Outcome.NOT_FOUND, map("succeeded"));
	}

	@Test
	void unreachableGatewayThrows() {
		Stripe3Payment down = new Stripe3Payment() {
			@Override
			protected PaymentIntent retrievePaymentIntent(String id, RequestOptions options) throws StripeException {
				throw new ApiConnectionException("timeout");
			}
		};
		assertThrows(IntegrationException.class, () -> down.lookup(null, configuration, attempt));
	}

	@Test
	@SuppressWarnings("unchecked")
	void capturedLateChargeIsRefundedWithCompensationKey() throws Exception {
		final RequestOptions[] used = new RequestOptions[1];
		final Map<String, Object>[] sent = new Map[1];
		Stripe3Payment stripe = new Stripe3Payment() {
			@Override
			protected Refund createRefund(Map<String, Object> params, RequestOptions options) {
				used[0] = options;
				sent[0] = params;
				Refund re = new Refund();
				re.setId("re_1");
				re.setStatus("succeeded");
				return re;
			}
		};
		GatewayPaymentStatus charged = GatewayPaymentStatus.succeeded("pi_123", new BigDecimal("12.34"), "succeeded");

		Transaction refund = stripe.compensate(null, configuration, attempt, charged);

		assertEquals(TransactionType.REFUND, refund.getTransactionType());
		assertEquals(new BigDecimal("12.34"), refund.getAmount());
		assertEquals("re_1", refund.getTransactionDetails().get("TRANSACTIONID"));
		assertEquals("comp-" + attempt.getAttemptRef(), used[0].getIdempotencyKey());
		assertEquals("pi_123", sent[0].get("payment_intent"));
		assertNull(sent[0].get("amount"));
	}

	@Test
	void authorisedLateChargeIsCancelled() throws Exception {
		final RequestOptions[] used = new RequestOptions[1];
		Stripe3Payment stripe = new Stripe3Payment() {
			@Override
			protected PaymentIntent retrievePaymentIntent(String id, RequestOptions options) {
				return intent("requires_capture");
			}

			@Override
			protected PaymentIntent cancelPaymentIntent(PaymentIntent pi, RequestOptions options) {
				used[0] = options;
				pi.setStatus("canceled");
				return pi;
			}
		};
		GatewayPaymentStatus held = GatewayPaymentStatus.authorized("pi_123", new BigDecimal("12.34"),
				"requires_capture");

		Transaction refund = stripe.compensate(null, configuration, attempt, held);

		assertEquals("canceled", refund.getTransactionDetails().get("TRNAPPROVED"));
		assertEquals("comp-" + attempt.getAttemptRef(), used[0].getIdempotencyKey());
	}

	@Test
	void captureOptionsCarryTheAttemptReference() {
		assertEquals("ref-1", Stripe3Payment.requestOptions("sk", "ref-1").getIdempotencyKey());
		assertNull(Stripe3Payment.requestOptions("sk").getIdempotencyKey());
	}

	@Test
	void statementSuffixIsShortAndClean() {
		assertEquals("Sandbox pr", Stripe3Payment.statementSuffix("Sandbox proof store"));
		assertEquals("Bobs", Stripe3Payment.statementSuffix("<Bob's*>"));
		assertNull(Stripe3Payment.statementSuffix("  "));
		assertNull(Stripe3Payment.statementSuffix(null));
	}
}
