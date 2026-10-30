package com.salesmanager.core.business.services.order.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import com.salesmanager.core.business.services.order.OrderCheckoutSteps;
import com.salesmanager.core.business.services.order.idempotency.OrderIdempotencyService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;

/** The explicit re-check may settle PAYMENT_REVIEW orders, and settled orders get their confirmation. */
class PendingOrderReconcilerRecheckTest {

	private OrderCheckoutSteps steps;
	private PendingOrderQueries queries;
	private PaymentAttemptService attempts;
	private PaymentGateways gateways;
	private ApplicationEventPublisher events;
	private PendingOrderReconciler reconciler;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		steps = mock(OrderCheckoutSteps.class);
		queries = mock(PendingOrderQueries.class);
		attempts = mock(PaymentAttemptService.class);
		gateways = mock(PaymentGateways.class);
		events = mock(ApplicationEventPublisher.class);
		reconciler = new PendingOrderReconciler(steps, queries, mock(OrderIdempotencyService.class), attempts,
				gateways, mock(ObjectProvider.class));
		reconciler.setApplicationEventPublisher(events);
	}

	private PaymentAttempt attempt(long orderId, PaymentAttemptStatus status) {
		PaymentAttempt a = new PaymentAttempt();
		a.setId(orderId * 10);
		a.setOrderId(orderId);
		a.setAttemptRef("ref-" + orderId);
		a.setAmount(new BigDecimal("10.00"));
		a.setStatus(status);
		a.setDateCreated(new Date(System.currentTimeMillis() - 2 * 24 * 60 * 60_000L));
		when(attempts.latestForOrder(orderId)).thenReturn(a);
		return a;
	}

	@Test
	void recheckSettlesAReviewOrderTheGatewayConfirms() throws Exception {
		PaymentAttempt a = attempt(40L, PaymentAttemptStatus.UNKNOWN);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.succeeded("pi_40", new BigDecimal("10.00"), "ok"));
		when(steps.reconcileConfirmedPayment(eq(40L), eq(400L), any(Transaction.class),
				eq(PendingOrderReconciler.CONFIRMED_COMMENT), eq(true))).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.recheckOrder(40L);

		assertEquals(1, r.finalised);
		ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(event.capture());
		assertEquals(40L, ((PendingOrderPaidEvent) event.getValue()).getOrderId());
	}

	@Test
	void recheckFailsAReviewOrderTheGatewayDeclined() throws Exception {
		PaymentAttempt a = attempt(41L, PaymentAttemptStatus.UNKNOWN);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.failed("pi_41", "card_declined"));
		when(steps.reconcileUnpaidOrder(eq(41L), anyString(), eq(410L), eq(true))).thenReturn(true);

		assertEquals(1, reconciler.recheckOrder(41L).failed);
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void recheckFinalisesAReviewOrderWithARecordedPayment() throws Exception {
		when(queries.hasSuccessfulPayment(42L)).thenReturn(true);
		when(steps.reconcilePaidOrder(42L, PendingOrderReconciler.PAID_COMMENT, true)).thenReturn(true);

		assertEquals(1, reconciler.recheckOrder(42L).finalised);
		verify(events).publishEvent(any(Object.class));
	}

	@Test
	void webhookAndScheduledPathsNeverClaimFromReview() throws Exception {
		PaymentAttempt a = attempt(43L, PaymentAttemptStatus.UNKNOWN);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.succeeded("pi_43", new BigDecimal("10.00"), "ok"));
		when(steps.reconcileConfirmedPayment(eq(43L), eq(430L), any(Transaction.class),
				eq(PendingOrderReconciler.CONFIRMED_COMMENT))).thenReturn(true);

		assertEquals(1, reconciler.reconcileOrder(43L).finalised);

		verify(steps, never()).reconcileConfirmedPayment(anyLong(), anyLong(), any(), anyString(), anyBoolean());
		verify(steps, never()).reconcilePaidOrder(anyLong(), anyString(), anyBoolean());
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString(), any(), anyBoolean());
		// a pending order settled as paid gets the confirmation the checkout never sent
		verify(events).publishEvent(any(Object.class));
	}

	@Test
	void orderAlreadySettledElsewhereSendsNothing() throws Exception {
		when(queries.hasSuccessfulPayment(44L)).thenReturn(true);
		when(steps.reconcilePaidOrder(44L, PendingOrderReconciler.PAID_COMMENT)).thenReturn(false);

		assertEquals(1, reconciler.reconcileOrder(44L).skipped);
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void reviewOrderStillWithoutAnswerStaysInReview() throws Exception {
		PaymentAttempt a = attempt(45L, PaymentAttemptStatus.UNKNOWN);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.pending(null, "no status lookup"));

		PendingOrderReconciler.Result r = reconciler.recheckOrder(45L);

		assertEquals(0, r.finalised + r.failed);
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString(), any(), anyBoolean());
	}
}
