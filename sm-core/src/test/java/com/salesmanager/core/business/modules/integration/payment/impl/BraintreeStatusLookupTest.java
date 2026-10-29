package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.braintreegateway.Transaction.Status;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus.Outcome;

class BraintreeStatusLookupTest {

	private static Outcome map(Status status) {
		return BraintreePayment.toStatus(status, "bt_1", BigDecimal.TEN).getOutcome();
	}

	@Test
	void settledOrSettlingSalesSucceeded() {
		assertEquals(Outcome.SUCCEEDED, map(Status.SUBMITTED_FOR_SETTLEMENT));
		assertEquals(Outcome.SUCCEEDED, map(Status.SETTLING));
		assertEquals(Outcome.SUCCEEDED, map(Status.SETTLED));
		assertEquals(Outcome.SUCCEEDED, map(Status.AUTHORIZED));
	}

	@Test
	void authorisedIsHeldNotCaptured() {
		GatewayPaymentStatus authorised = BraintreePayment.toStatus(Status.AUTHORIZED, "bt_1", BigDecimal.TEN);
		assertFalse(authorised.isCaptured());
		GatewayPaymentStatus settled = BraintreePayment.toStatus(Status.SETTLED, "bt_1", BigDecimal.TEN);
		assertTrue(settled.isCaptured());
		assertEquals("bt_1", settled.getGatewayTransactionId());
		assertEquals(BigDecimal.TEN, settled.getAmount());
	}

	@Test
	void declinedRejectedOrVoidedFailed() {
		assertEquals(Outcome.FAILED, map(Status.FAILED));
		assertEquals(Outcome.FAILED, map(Status.GATEWAY_REJECTED));
		assertEquals(Outcome.FAILED, map(Status.PROCESSOR_DECLINED));
		assertEquals(Outcome.FAILED, map(Status.VOIDED));
	}

	@Test
	void anythingElseIsPending() {
		assertEquals(Outcome.PENDING, map(Status.AUTHORIZING));
		assertEquals(Outcome.PENDING, map(Status.UNRECOGNIZED));
		assertEquals(Outcome.PENDING, map(null));
	}
}
