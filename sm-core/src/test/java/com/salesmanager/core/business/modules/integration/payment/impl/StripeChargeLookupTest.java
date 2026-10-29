package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeSearchParams;

class StripeChargeLookupTest {

	private static final String REF = "11111111-2222-3333-4444-555555555555";

	private final IntegrationConfiguration configuration = new IntegrationConfiguration();
	private final PaymentAttempt attempt = new PaymentAttempt();

	StripeChargeLookupTest() {
		configuration.setIntegrationKeys(Collections.singletonMap("secretKey", "sk_test_x"));
		attempt.setAttemptRef(REF);
		attempt.setCurrency("USD");
		attempt.setAmount(new BigDecimal("12.34"));
	}

	private static Charge charge(String id, String status, boolean captured) {
		Charge c = new Charge();
		c.setId(id);
		c.setStatus(status);
		c.setCaptured(captured);
		c.setCurrency("usd");
		c.setAmount(1234L);
		c.setAmountCaptured(captured ? 1234L : 0L);
		c.setRefunded(false);
		return c;
	}

	private static StripePayment returning(Charge... charges) {
		return new StripePayment() {
			@Override
			protected List<Charge> searchCharges(ChargeSearchParams params, RequestOptions options) {
				assertEquals("metadata['attempt_ref']:'" + REF + "'", params.getQuery());
				return Arrays.asList(charges);
			}
		};
	}

	@Test
	void capturedChargeIsSucceededAndCaptured() throws Exception {
		GatewayPaymentStatus s = returning(charge("ch_1", "succeeded", true)).lookup(null, configuration, attempt);
		assertEquals(Outcome.SUCCEEDED, s.getOutcome());
		assertTrue(s.isCaptured());
		assertEquals("ch_1", s.getGatewayTransactionId());
		assertEquals(new BigDecimal("12.34"), s.getAmount());
	}

	@Test
	void uncapturedChargeIsAuthorizedOnly() throws Exception {
		GatewayPaymentStatus s = returning(charge("ch_1", "succeeded", false)).lookup(null, configuration, attempt);
		assertEquals(Outcome.SUCCEEDED, s.getOutcome());
		assertFalse(s.isCaptured());
		assertEquals(new BigDecimal("12.34"), s.getAmount());
	}

	@Test
	void pendingFailedAndRefundedCharges() throws Exception {
		assertEquals(Outcome.PENDING,
				returning(charge("ch_1", "pending", false)).lookup(null, configuration, attempt).getOutcome());
		assertEquals(Outcome.FAILED,
				returning(charge("ch_1", "failed", false)).lookup(null, configuration, attempt).getOutcome());
		Charge refunded = charge("ch_1", "succeeded", true);
		refunded.setRefunded(true);
		assertEquals(Outcome.FAILED, returning(refunded).lookup(null, configuration, attempt).getOutcome());
	}

	@Test
	void noChargeIsNotFound() throws Exception {
		assertEquals(Outcome.NOT_FOUND, returning().lookup(null, configuration, attempt).getOutcome());
	}

	@Test
	void successWinsOverFailedRetry() throws Exception {
		GatewayPaymentStatus s = returning(charge("ch_1", "failed", false), charge("ch_2", "succeeded", true))
				.lookup(null, configuration, attempt);
		assertEquals(Outcome.SUCCEEDED, s.getOutcome());
		assertEquals("ch_2", s.getGatewayTransactionId());
	}

	@Test
	void missingAttemptRefStaysPending() throws Exception {
		attempt.setAttemptRef(null);
		assertEquals(Outcome.PENDING, new StripePayment().lookup(null, configuration, attempt).getOutcome());
	}

	@Test
	void quotesInReferenceAreEscaped() {
		assertEquals("metadata['attempt_ref']:'a\\'b'", StripePayment.attemptQuery("a'b"));
	}

	@Test
	void networkErrorIsRaised() {
		StripePayment module = new StripePayment() {
			@Override
			protected List<Charge> searchCharges(ChargeSearchParams params, RequestOptions options)
					throws StripeException {
				throw new ApiConnectionException("down");
			}
		};
		assertThrows(IntegrationException.class, () -> module.lookup(null, configuration, attempt));
	}

	@Test
	void compensationRefundsTheChargeWithIdempotencyKey() throws Exception {
		Map<String, Object>[] seen = new Map[1];
		RequestOptions[] opts = new RequestOptions[1];
		StripePayment module = new StripePayment() {
			@Override
			protected Refund createRefund(Map<String, Object> params, RequestOptions options) {
				seen[0] = params;
				opts[0] = options;
				Refund r = new Refund();
				r.setId("re_1");
				r.setStatus("succeeded");
				return r;
			}
		};
		GatewayPaymentStatus status = GatewayPaymentStatus.succeeded("ch_1", new BigDecimal("12.34"), "succeeded");
		Transaction t = module.compensate(null, configuration, attempt, status);

		assertEquals("ch_1", seen[0].get("charge"));
		assertNull(seen[0].get("amount"));
		assertEquals("comp-" + REF, opts[0].getIdempotencyKey());
		assertEquals(TransactionType.REFUND, t.getTransactionType());
		assertEquals("re_1", t.getTransactionDetails().get("TRANSACTIONID"));
		assertEquals("ch_1", t.getTransactionDetails().get("REFUNDEDTRANSACTION"));
		assertEquals(new BigDecimal("12.34"), t.getAmount());
	}

	@Test
	void compensationWithoutChargeIdFails() {
		GatewayPaymentStatus status = GatewayPaymentStatus.succeeded(null, null, "succeeded");
		assertThrows(IntegrationException.class, () -> new StripePayment().compensate(null, configuration, attempt, status));
	}
}
