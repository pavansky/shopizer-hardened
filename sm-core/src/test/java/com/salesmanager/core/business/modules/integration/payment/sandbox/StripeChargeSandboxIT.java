package com.salesmanager.core.business.modules.integration.payment.sandbox;

import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.attempt;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.newAttemptRef;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.payment;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.proof;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.salesmanager.core.business.modules.integration.payment.impl.StripePayment;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus.Outcome;

/**
 * Legacy Stripe Charge module against the real Stripe test mode, including the
 * search-based lookup. Runs only with STRIPE_TEST_SECRET_KEY set, through -Psandbox.
 */
@EnabledIfEnvironmentVariable(named = SandboxFixtures.STRIPE_KEY_VAR, matches = ".+")
class StripeChargeSandboxIT {

	private static final BigDecimal AMOUNT = new BigDecimal("12.34");
	private static final String GW = "stripe-charge";
	/** Stripe documents search freshness of up to about a minute. */
	private static final long SEARCH_WAIT_MS = 90_000;

	private static IntegrationConfiguration configuration;
	private final StripePayment module = new StripePayment();
	private final MerchantStore store = store();

	@BeforeAll
	static void keys() {
		configuration = SandboxFixtures.stripeConfiguration();
	}

	/** Polls the lookup until the search index shows the charge. */
	private GatewayPaymentStatus lookupWhenIndexed(PaymentAttempt attempt) throws Exception {
		long started = System.currentTimeMillis();
		GatewayPaymentStatus status = module.lookup(store, configuration, attempt);
		while (status.getOutcome() == Outcome.NOT_FOUND && System.currentTimeMillis() - started < SEARCH_WAIT_MS) {
			Thread.sleep(5_000);
			status = module.lookup(store, configuration, attempt);
		}
		proof(GW, "search visible after", (System.currentTimeMillis() - started) / 1000 + " s");
		return status;
	}

	@Test
	void capturedChargeIsFoundBySearchAndCompensated() throws Exception {
		String ref = newAttemptRef();
		Transaction charged = module.authorizeAndCapture(store, null, null, AMOUNT,
				payment("stripe_token", "tok_visa", ref, AMOUNT), configuration, null);
		String chargeId = charged.getTransactionDetails().get("TRNORDERNUMBER");
		PaymentAttempt attempt = attempt(ref, chargeId, AMOUNT);

		GatewayPaymentStatus status = lookupWhenIndexed(attempt);
		assertEquals(Outcome.SUCCEEDED, status.getOutcome());
		assertTrue(status.isCaptured());
		assertEquals(chargeId, status.getGatewayTransactionId());
		assertEquals(0, AMOUNT.compareTo(status.getAmount()));
		proof(GW, "captured charge lookup", chargeId + " -> " + status + " amount " + status.getAmount());

		Transaction refund = module.compensate(store, configuration, attempt, status);
		assertEquals(TransactionType.REFUND, refund.getTransactionType());
		proof(GW, "compensation refund", chargeId + " -> refund " + refund.getTransactionDetails().get("TRANSACTIONID")
				+ " " + refund.getTransactionDetails().get("TRNAPPROVED") + " amount " + refund.getAmount());
	}

	@Test
	void uncapturedChargeIsAuthorisedAndReleasedByCompensation() throws Exception {
		String ref = newAttemptRef();
		Transaction held = module.authorize(store, null, null, AMOUNT,
				payment("stripe_token", "tok_visa", ref, AMOUNT), configuration, null);
		String chargeId = held.getTransactionDetails().get("TRNORDERNUMBER");
		PaymentAttempt attempt = attempt(ref, chargeId, AMOUNT);

		GatewayPaymentStatus status = lookupWhenIndexed(attempt);
		assertEquals(Outcome.SUCCEEDED, status.getOutcome());
		assertFalse(status.isCaptured());
		proof(GW, "uncaptured charge lookup", chargeId + " -> " + status + " captured=false");

		Transaction released = module.compensate(store, configuration, attempt, status);
		assertEquals(TransactionType.REFUND, released.getTransactionType());
		proof(GW, "hold released", chargeId + " -> refund " + released.getTransactionDetails().get("TRANSACTIONID")
				+ " " + released.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	void unknownReferenceIsNotFound() throws Exception {
		String ref = newAttemptRef();
		GatewayPaymentStatus status = module.lookup(store, configuration, attempt(ref, null, AMOUNT));
		assertEquals(Outcome.NOT_FOUND, status.getOutcome());
		proof(GW, "unknown reference", ref + " -> " + status);
	}
}
