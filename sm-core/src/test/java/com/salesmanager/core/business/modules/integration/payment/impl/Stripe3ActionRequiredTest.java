package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.PaymentOutcomeUnknownException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;

class Stripe3ActionRequiredTest {

	private final MerchantStore store = new MerchantStore();
	private final IntegrationConfiguration configuration = new IntegrationConfiguration();
	private final Payment payment = new Payment();
	private final AtomicInteger captures = new AtomicInteger();

	Stripe3ActionRequiredTest() {
		Currency usd = new Currency();
		usd.setCurrency(java.util.Currency.getInstance("USD"));
		store.setCurrency(usd);
		store.setStorename("Demo");
		configuration.setIntegrationKeys(Collections.singletonMap("secretKey", "sk_test_x"));
		Map<String, String> meta = new HashMap<>();
		meta.put("paymentToken", "pi_123");
		meta.put(PaymentAttempt.META_ATTEMPT_REF, "ref-1");
		payment.setPaymentMetaData(meta);
	}

	private Stripe3Payment returning(String status) {
		return new Stripe3Payment() {
			@Override
			protected PaymentIntent retrievePaymentIntent(String id, RequestOptions options) {
				PaymentIntent pi = new PaymentIntent();
				pi.setId(id);
				pi.setStatus(status);
				pi.setClientSecret(id + "_secret_x");
				pi.setAmount(1000L);
				pi.setAmountCapturable("requires_capture".equals(status) ? 1000L : 0L);
				pi.setMetadata(Collections.singletonMap("cart_code", "cart-1"));
				return pi;
			}

			@Override
			protected PaymentIntent capturePaymentIntent(PaymentIntent intent, PaymentIntentCaptureParams params,
					RequestOptions options) {
				captures.incrementAndGet();
				assertEquals("ref-1", options.getIdempotencyKey());
				intent.setStatus("succeeded");
				return intent;
			}
		};
	}

	@ParameterizedTest
	@ValueSource(strings = { "requires_action", "requires_payment_method", "requires_confirmation" })
	void intentTheCustomerHasNotFinishedIsNotPaidYet(String status) {
		PaymentActionRequiredException thrown = assertThrows(PaymentActionRequiredException.class,
				() -> returning(status).authorizeAndCapture(store, null, null, BigDecimal.TEN, payment, configuration,
						null));

		assertEquals("pi_123", thrown.getPaymentIntentId());
		assertEquals("pi_123_secret_x", thrown.getClientSecret());
		assertEquals(status, thrown.getStatus());
		assertEquals(0, captures.get());
	}

	@Test
	void authorizeOnlyAlsoRefusesAnUnfinishedIntent() {
		// the authorize path reads the intent id from stripe_token
		payment.getPaymentMetaData().put("stripe_token", "pi_123");
		assertThrows(PaymentActionRequiredException.class, () -> returning("requires_action").authorize(store, null,
				null, BigDecimal.TEN, payment, configuration, null));
	}

	@Test
	void authorisedIntentIsCapturedUnderTheAttemptKey() throws Exception {
		Transaction tx = returning("requires_capture").authorizeAndCapture(store, null, null, BigDecimal.TEN, payment,
				configuration, null);

		assertEquals("succeeded", tx.getTransactionDetails().get("TRNAPPROVED"));
		assertEquals(1, captures.get());
	}

	@Test
	void intentStartedForAnotherCartIsDeclinedBeforeItsSecretOrMoneyIsTouched() {
		// a checkout of cart-2 sends the PaymentIntent created for cart-1
		payment.getPaymentMetaData().put(Stripe3Payment.META_CART_CODE, "cart-2");

		IntegrationException thrown = assertThrows(IntegrationException.class,
				() -> returning("requires_action").authorizeAndCapture(store, null, null, BigDecimal.TEN, payment,
						configuration, null));

		// a decline, not "action required": the other cart's client secret is not handed out
		assertFalse(thrown instanceof PaymentActionRequiredException);
		assertEquals(IntegrationException.EXCEPTION_PAYMENT_DECLINED, thrown.getExceptionType());
		assertEquals(0, captures.get());
	}

	@Test
	void intentOfTheSameCartIsCaptured() throws Exception {
		payment.getPaymentMetaData().put(Stripe3Payment.META_CART_CODE, "cart-1");

		returning("requires_capture").authorizeAndCapture(store, null, null, BigDecimal.TEN, payment, configuration,
				null);

		assertEquals(1, captures.get());
	}

	@Test
	void concurrentRequestWithTheSameKeyIsAnUnknownOutcome() {
		Stripe3Payment stripe = new Stripe3Payment() {
			@Override
			protected PaymentIntent retrievePaymentIntent(String id, RequestOptions options) {
				PaymentIntent pi = new PaymentIntent();
				pi.setId(id);
				pi.setStatus("requires_capture");
				pi.setAmountCapturable(1000L);
				return pi;
			}

			@Override
			protected PaymentIntent capturePaymentIntent(PaymentIntent intent, PaymentIntentCaptureParams params,
					RequestOptions options) throws StripeException {
				throw new IdempotencyException("in progress", "req_1", "idempotency_error", 409);
			}
		};

		Exception thrown = assertThrows(Exception.class, () -> stripe.authorizeAndCapture(store, null, null,
				BigDecimal.TEN, payment, configuration, null));
		assertTrue(thrown instanceof PaymentOutcomeUnknownException);
	}
}
