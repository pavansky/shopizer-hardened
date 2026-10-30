package com.salesmanager.core.business.modules.integration.payment.sandbox;

import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.attempt;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.newAttemptRef;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.payment;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.proof;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.salesmanager.core.business.modules.integration.payment.impl.PaymentActionRequiredException;
import com.salesmanager.core.business.modules.integration.payment.impl.Stripe3Payment;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus.Outcome;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

/**
 * Stripe PaymentIntent module against the real Stripe test mode. Runs only with
 * STRIPE_TEST_SECRET_KEY set to an sk_test_ key, through -Psandbox.
 */
@EnabledIfEnvironmentVariable(named = SandboxFixtures.STRIPE_KEY_VAR, matches = ".+")
class Stripe3SandboxIT {

	private static final BigDecimal AMOUNT = new BigDecimal("12.34");
	private static final String GW = "stripe3";

	private static IntegrationConfiguration configuration;
	private static RequestOptions options;
	private final Stripe3Payment module = new Stripe3Payment();
	private final MerchantStore store = store();

	@BeforeAll
	static void keys() {
		configuration = SandboxFixtures.stripeConfiguration();
		options = RequestOptions.builder().setApiKey(SandboxFixtures.stripeTestKey()).build();
	}

	/** Creates and confirms a PaymentIntent the way the storefront's Stripe.js would. */
	private static PaymentIntent confirmedIntent(String paymentMethod, PaymentIntentCreateParams.CaptureMethod capture,
			String attemptRef) throws StripeException {
		PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
				.setAmount(1234L)
				.setCurrency("usd")
				.setPaymentMethod(paymentMethod)
				.setConfirm(true)
				.setCaptureMethod(capture)
				.setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
						.setEnabled(true)
						.setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
						.build())
				.putMetadata("attempt_ref", attemptRef)
				.build();
		return PaymentIntent.create(params, options);
	}

	@Test
	void automaticCaptureLooksUpAsSucceededWithAmount() throws Exception {
		String ref = newAttemptRef();
		PaymentIntent pi = confirmedIntent("pm_card_visa", PaymentIntentCreateParams.CaptureMethod.AUTOMATIC, ref);

		GatewayPaymentStatus status = module.lookup(store, configuration, attempt(ref, pi.getId(), AMOUNT));

		assertEquals(Outcome.SUCCEEDED, status.getOutcome());
		assertTrue(status.isCaptured());
		assertEquals(0, AMOUNT.compareTo(status.getAmount()));
		proof(GW, "automatic capture lookup", pi.getId() + " " + pi.getStatus() + " -> " + status + " amount "
				+ status.getAmount());
	}

	@Test
	void manualCaptureThenModuleCaptureThenPartialAndFullRefund() throws Exception {
		String ref = newAttemptRef();
		PaymentIntent pi = confirmedIntent("pm_card_visa", PaymentIntentCreateParams.CaptureMethod.MANUAL, ref);
		PaymentAttempt attempt = attempt(ref, pi.getId(), AMOUNT);

		GatewayPaymentStatus held = module.lookup(store, configuration, attempt);
		assertEquals(Outcome.SUCCEEDED, held.getOutcome());
		assertFalse(held.isCaptured());
		proof(GW, "manual capture lookup", pi.getId() + " " + pi.getStatus() + " -> " + held + " captured=false");

		Transaction captured = module.authorizeAndCapture(store, null, null, AMOUNT,
				payment("stripe_token", pi.getId(), ref, AMOUNT), configuration, null);
		assertEquals(TransactionType.AUTHORIZECAPTURE, captured.getTransactionType());
		assertEquals("succeeded", captured.getTransactionDetails().get("TRNAPPROVED"));

		GatewayPaymentStatus after = module.lookup(store, configuration, attempt);
		assertEquals(Outcome.SUCCEEDED, after.getOutcome());
		assertTrue(after.isCaptured());
		proof(GW, "module capture", pi.getId() + " -> " + after + " amount " + after.getAmount());

		Transaction partial = module.refund(true, store, captured, null, new BigDecimal("2.00"), configuration, null);
		assertEquals(TransactionType.REFUND, partial.getTransactionType());
		Transaction rest = module.refund(false, store, captured, null, new BigDecimal("10.34"), configuration, null);
		assertEquals(TransactionType.REFUND, rest.getTransactionType());
		proof(GW, "partial+full refund", partial.getTransactionDetails().get("TRANSACTIONID") + " 2.00 "
				+ partial.getTransactionDetails().get("TRNAPPROVED") + ", "
				+ rest.getTransactionDetails().get("TRANSACTIONID") + " 10.34 "
				+ rest.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	void lateCapturedChargeIsCompensatedByRefund() throws Exception {
		String ref = newAttemptRef();
		PaymentIntent pi = confirmedIntent("pm_card_visa", PaymentIntentCreateParams.CaptureMethod.AUTOMATIC, ref);
		PaymentAttempt attempt = attempt(ref, pi.getId(), AMOUNT);
		GatewayPaymentStatus status = module.lookup(store, configuration, attempt);

		Transaction refund = module.compensate(store, configuration, attempt, status);

		assertEquals(TransactionType.REFUND, refund.getTransactionType());
		assertNotNull(refund.getTransactionDetails().get("TRANSACTIONID"));
		// same idempotency key: a second compensation returns the same refund
		Transaction again = module.compensate(store, configuration, attempt, status);
		assertEquals(refund.getTransactionDetails().get("TRANSACTIONID"), again.getTransactionDetails().get("TRANSACTIONID"));
		proof(GW, "late charge compensation", pi.getId() + " -> refund "
				+ refund.getTransactionDetails().get("TRANSACTIONID") + " "
				+ refund.getTransactionDetails().get("TRNAPPROVED") + " amount " + refund.getAmount());
	}

	@Test
	void lateAuthorisedChargeIsCompensatedByCancel() throws Exception {
		String ref = newAttemptRef();
		PaymentIntent pi = confirmedIntent("pm_card_visa", PaymentIntentCreateParams.CaptureMethod.MANUAL, ref);
		PaymentAttempt attempt = attempt(ref, pi.getId(), AMOUNT);
		GatewayPaymentStatus status = module.lookup(store, configuration, attempt);

		Transaction released = module.compensate(store, configuration, attempt, status);

		assertEquals("canceled", released.getTransactionDetails().get("TRNAPPROVED"));
		assertEquals(Outcome.FAILED, module.lookup(store, configuration, attempt).getOutcome());
		proof(GW, "late hold compensation", pi.getId() + " -> " + released.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	void declinedCardLooksUpAsFailed() throws Exception {
		String ref = newAttemptRef();
		CardException declined = assertThrows(CardException.class, () -> confirmedIntent("pm_card_chargeDeclined",
				PaymentIntentCreateParams.CaptureMethod.AUTOMATIC, ref));
		String intentId = declined.getStripeError().getPaymentIntent().getId();

		GatewayPaymentStatus status = module.lookup(store, configuration, attempt(ref, intentId, AMOUNT));

		assertEquals(Outcome.FAILED, status.getOutcome());
		proof(GW, "declined card", intentId + " decline_code=" + declined.getDeclineCode() + " -> " + status);
	}

	@Test
	void unknownReferenceIsNotFound() throws Exception {
		String ref = newAttemptRef();
		GatewayPaymentStatus status = module.lookup(store, configuration,
				attempt(ref, "pi_sandboxproof" + ref.replace("-", "").substring(0, 16), AMOUNT));

		assertEquals(Outcome.NOT_FOUND, status.getOutcome());
		proof(GW, "unknown reference", ref + " -> " + status);
	}

	@Test
	void threeDSecureChallengeIsSurfacedAsActionRequiredNotADecline() throws Exception {
		String ref = newAttemptRef();
		PaymentIntent pi = confirmedIntent("pm_card_threeDSecure2Required",
				PaymentIntentCreateParams.CaptureMethod.MANUAL, ref);
		assertEquals("requires_action", pi.getStatus());

		PaymentActionRequiredException action = assertThrows(PaymentActionRequiredException.class,
				() -> module.authorizeAndCapture(store, null, null, AMOUNT,
						payment("stripe_token", pi.getId(), ref, AMOUNT), configuration, null));

		assertEquals(pi.getId(), action.getPaymentIntentId());
		assertEquals("requires_action", action.getStatus());
		assertNotNull(action.getClientSecret());
		// nothing was taken: the reconciler keeps waiting instead of failing the order
		assertEquals(Outcome.PENDING, module.lookup(store, configuration, attempt(ref, pi.getId(), AMOUNT)).getOutcome());
		proof(GW, "3-D Secure required", pi.getId() + " " + pi.getStatus() + " -> payment_action_required");
	}
}
