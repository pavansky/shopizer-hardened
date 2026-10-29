package com.salesmanager.core.business.services.order.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.salesmanager.core.business.services.order.OrderCheckoutSteps;
import com.salesmanager.core.business.services.order.idempotency.OrderIdempotencyService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;

class PendingOrderReconcilerTest {

	private OrderCheckoutSteps steps;
	private PendingOrderQueries queries;
	private OrderIdempotencyService idempotency;
	private ReconciliationMetrics metrics;
	private PaymentAttemptService attempts;
	private PaymentGateways gateways;
	private PendingOrderReconciler reconciler;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		steps = mock(OrderCheckoutSteps.class);
		queries = mock(PendingOrderQueries.class);
		idempotency = mock(OrderIdempotencyService.class);
		metrics = mock(ReconciliationMetrics.class);
		attempts = mock(PaymentAttemptService.class);
		gateways = mock(PaymentGateways.class);
		ObjectProvider<ReconciliationMetrics> provider = mock(ObjectProvider.class);
		org.mockito.Mockito.doAnswer(inv -> {
			((java.util.function.Consumer<ReconciliationMetrics>) inv.getArgument(0)).accept(metrics);
			return null;
		}).when(provider).ifAvailable(any());
		reconciler = new PendingOrderReconciler(steps, queries, idempotency, attempts, gateways, provider);
	}

	@Test
	void paymentFoundFinalisesOrder() throws Exception {
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(7L));
		when(queries.hasSuccessfulPayment(7L)).thenReturn(true);
		when(steps.reconcilePaidOrder(7L, PendingOrderReconciler.PAID_COMMENT)).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.finalised);
		verify(steps).reconcilePaidOrder(7L, PendingOrderReconciler.PAID_COMMENT);
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString());
		verify(idempotency, never()).releaseKeysForOrder(anyLong());
		verify(metrics).finalised();
	}

	@Test
	void noPaymentMarksFailedAndReleasesKey() throws Exception {
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(8L));
		when(queries.hasSuccessfulPayment(8L)).thenReturn(false);
		when(steps.reconcileUnpaidOrder(8L, PendingOrderReconciler.UNPAID_REASON)).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.failed);
		verify(steps).reconcileUnpaidOrder(8L, PendingOrderReconciler.UNPAID_REASON);
		verify(idempotency).releaseKeysForOrder(8L);
		verify(metrics).failed();
	}

	@Test
	void orderClaimedElsewhereIsSkippedAndKeyKept() throws Exception {
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(9L));
		when(steps.reconcileUnpaidOrder(9L, PendingOrderReconciler.UNPAID_REASON)).thenReturn(false);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.skipped);
		verify(idempotency, never()).releaseKeysForOrder(anyLong());
	}

	@Test
	void oneFailingOrderDoesNotStopTheBatch() throws Exception {
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(1L, 2L, 3L));
		when(queries.hasSuccessfulPayment(1L)).thenReturn(true);
		when(queries.hasSuccessfulPayment(2L)).thenThrow(new IllegalStateException("db"));
		when(queries.hasSuccessfulPayment(3L)).thenReturn(false);
		when(steps.reconcilePaidOrder(eq(1L), anyString())).thenThrow(new RuntimeException("lock timeout"));
		when(steps.reconcileUnpaidOrder(eq(3L), anyString())).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(2, r.errors);
		assertEquals(1, r.failed);
		verify(steps).reconcileUnpaidOrder(3L, PendingOrderReconciler.UNPAID_REASON);
		verify(idempotency).releaseKeysForOrder(3L);
	}

	@Test
	void keyReleaseFailureDoesNotCountAsOrderError() throws Exception {
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(4L));
		when(steps.reconcileUnpaidOrder(eq(4L), anyString())).thenReturn(true);
		when(idempotency.releaseKeysForOrder(4L)).thenThrow(new RuntimeException("db"));

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.failed);
		assertEquals(0, r.errors);
	}

	@Test
	void pagesThroughFullBatchesByLastId() throws Exception {
		set("batchSize", 2);
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), eq(2))).thenReturn(Arrays.asList(1L, 2L));
		when(queries.findStalePendingOrderIds(any(Date.class), eq(2L), eq(2))).thenReturn(Collections.singletonList(5L));
		when(steps.reconcileUnpaidOrder(anyLong(), anyString())).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(3, r.failed);
	}

	@Test
	void graceWindowIsApplied() throws Exception {
		when(queries.findStalePendingOrderIds(any(Date.class), anyLong(), anyInt())).thenReturn(Collections.emptyList());
		long before = System.currentTimeMillis();

		reconciler.reconcilePendingOrders();

		ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
		verify(queries).findStalePendingOrderIds(cutoff.capture(), eq(0L), eq(100));
		long age = before - cutoff.getValue().getTime();
		assertTrue(age >= 15 * 60_000L - 1000 && age <= 15 * 60_000L + 1000, "cutoff about 15 minutes ago");
	}

	@Test
	void disabledFlagDoesNothing() throws Exception {
		set("enabled", false);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(0, r.finalised + r.failed + r.skipped + r.errors);
		verifyNoInteractions(queries, steps, idempotency);
	}

	@Test
	void cleanupUsesRetentionWindow() throws Exception {
		when(idempotency.deleteExpiredKeys(any(Date.class))).thenReturn(3);
		long before = System.currentTimeMillis();

		assertEquals(3, reconciler.cleanupIdempotencyKeys());

		ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
		verify(idempotency).deleteExpiredKeys(cutoff.capture());
		long age = before - cutoff.getValue().getTime();
		assertTrue(Math.abs(age - 48 * 3_600_000L) < 1000, "cutoff about 48 hours ago");
		verify(metrics).keysDeleted(3);
	}

	@Test
	void cleanupDisabledDoesNothing() throws Exception {
		set("cleanupEnabled", false);

		assertEquals(0, reconciler.cleanupIdempotencyKeys());
		verifyNoInteractions(idempotency);
	}

	private PaymentAttempt attempt(long orderId, PaymentAttemptStatus status, int ageMinutes) {
		PaymentAttempt a = new PaymentAttempt();
		a.setId(orderId * 10);
		a.setOrderId(orderId);
		a.setMerchantId(1);
		a.setModuleCode("braintree");
		a.setAttemptRef("ref-" + orderId);
		a.setAmount(new java.math.BigDecimal("10.00"));
		a.setStatus(status);
		a.setDateCreated(new Date(System.currentTimeMillis() - ageMinutes * 60_000L));
		return a;
	}

	private void pending(long orderId, PaymentAttempt a) {
		when(queries.findStalePendingOrderIds(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(orderId));
		when(attempts.latestForOrder(orderId)).thenReturn(a);
	}

	@Test
	void succeededAttemptFinalisesWithoutAskingTheGateway() throws Exception {
		pending(20L, attempt(20L, PaymentAttemptStatus.SUCCEEDED, 60));
		when(attempts.hasSucceeded(20L)).thenReturn(true);
		when(steps.reconcilePaidOrder(20L, PendingOrderReconciler.PAID_COMMENT)).thenReturn(true);

		assertEquals(1, reconciler.reconcilePendingOrders().finalised);
		verifyNoInteractions(gateways);
	}

	@Test
	void failedAttemptFailsOrderAndReleasesKey() throws Exception {
		pending(21L, attempt(21L, PaymentAttemptStatus.FAILED, 60));
		when(steps.reconcileUnpaidOrder(21L, PendingOrderReconciler.UNPAID_REASON)).thenReturn(true);

		assertEquals(1, reconciler.reconcilePendingOrders().failed);
		verify(idempotency).releaseKeysForOrder(21L);
		verifyNoInteractions(gateways);
	}

	@Test
	void gatewayConfirmedPaymentIsRecordedAndOrderFinalised() throws Exception {
		PaymentAttempt a = attempt(22L, PaymentAttemptStatus.UNKNOWN, 60);
		pending(22L, a);
		when(gateways.lookup(a)).thenReturn(
				GatewayPaymentStatus.succeeded("bt_777", new java.math.BigDecimal("10.00"), "SETTLED"));
		ArgumentCaptor<Transaction> tx = ArgumentCaptor.forClass(Transaction.class);
		when(steps.reconcileConfirmedPayment(eq(22L), eq(220L), tx.capture(),
				eq(PendingOrderReconciler.CONFIRMED_COMMENT))).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.finalised);
		assertEquals(1, r.confirmed);
		assertEquals(TransactionType.AUTHORIZECAPTURE, tx.getValue().getTransactionType());
		assertEquals(new java.math.BigDecimal("10.00"), tx.getValue().getAmount());
		assertEquals("bt_777", tx.getValue().getTransactionDetails().get("TRANSACTIONID"));
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString());
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString(), anyLong());
		verify(idempotency, never()).releaseKeysForOrder(anyLong());
		verify(metrics).confirmedByGateway();
	}

	@Test
	void gatewayFailedFailsOrderAndAttempt() throws Exception {
		PaymentAttempt a = attempt(23L, PaymentAttemptStatus.INITIATED, 60);
		pending(23L, a);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.failed("bt_1", "PROCESSOR_DECLINED"));
		when(steps.reconcileUnpaidOrder(eq(23L), anyString(), eq(230L))).thenReturn(true);

		assertEquals(1, reconciler.reconcilePendingOrders().failed);
		verify(idempotency).releaseKeysForOrder(23L);
	}

	@Test
	void notFoundOnAYoungAttemptWaits() throws Exception {
		PaymentAttempt a = attempt(24L, PaymentAttemptStatus.UNKNOWN, 20);
		pending(24L, a);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.notFound());

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.waiting);
		assertEquals(0, r.failed);
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString(), anyLong());
		verify(steps, never()).moveToPaymentReview(anyLong(), anyString());
	}

	@Test
	void notFoundOnAnOldAttemptFails() throws Exception {
		PaymentAttempt a = attempt(25L, PaymentAttemptStatus.UNKNOWN, 31);
		pending(25L, a);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.notFound());
		when(steps.reconcileUnpaidOrder(25L, PendingOrderReconciler.NOT_FOUND_REASON, 250L)).thenReturn(true);

		assertEquals(1, reconciler.reconcilePendingOrders().failed);
		verify(idempotency).releaseKeysForOrder(25L);
	}

	@Test
	void pendingAtTheGatewayWaitsWhileYoung() throws Exception {
		PaymentAttempt a = attempt(26L, PaymentAttemptStatus.UNKNOWN, 120);
		pending(26L, a);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.pending("pi_1", "processing"));

		assertEquals(1, reconciler.reconcilePendingOrders().waiting);
		verify(steps, never()).moveToPaymentReview(anyLong(), anyString());
	}

	@Test
	void pendingAtTheGatewayMovesAnOldOrderToReview() throws Exception {
		PaymentAttempt a = attempt(27L, PaymentAttemptStatus.UNKNOWN, 1441);
		pending(27L, a);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.pending("pi_1", "processing"));
		when(steps.moveToPaymentReview(eq(27L), anyString())).thenReturn(true);

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.review);
		verify(metrics).review();
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString(), anyLong());
		verify(idempotency, never()).releaseKeysForOrder(anyLong());
	}

	@Test
	void lookupFailureLeavesTheOrderUntouched() throws Exception {
		PaymentAttempt a = attempt(28L, PaymentAttemptStatus.UNKNOWN, 60);
		pending(28L, a);
		when(gateways.lookup(a)).thenThrow(new java.net.SocketTimeoutException("gateway down"));

		PendingOrderReconciler.Result r = reconciler.reconcilePendingOrders();

		assertEquals(1, r.lookupErrors);
		assertEquals(0, r.errors + r.failed + r.finalised + r.review);
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString());
		verify(steps, never()).reconcileUnpaidOrder(anyLong(), anyString(), anyLong());
		verify(steps, never()).reconcilePaidOrder(anyLong(), anyString());
		verify(steps, never()).moveToPaymentReview(anyLong(), anyString());
		verify(metrics).lookupError();
	}

	@Test
	void orphanLateChargeIsCompensated() throws Exception {
		PaymentAttempt a = attempt(30L, PaymentAttemptStatus.UNKNOWN, 60);
		when(attempts.findOrphans(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(a));
		GatewayPaymentStatus charged = GatewayPaymentStatus.succeeded("bt_9", new java.math.BigDecimal("10.00"),
				"SUBMITTED_FOR_SETTLEMENT");
		when(gateways.lookup(a)).thenReturn(charged);
		Transaction refund = new Transaction();
		refund.setTransactionType(TransactionType.REFUND);
		when(gateways.compensate(a, charged)).thenReturn(refund);

		PendingOrderReconciler.OrphanResult r = reconciler.compensateOrphanAttempts();

		assertEquals(1, r.compensated);
		verify(steps).recordCompensation(30L, 300L, refund, PendingOrderReconciler.COMPENSATED_COMMENT);
		verify(metrics).compensated();
	}

	@Test
	void orphanThatCannotBeCompensatedIsOnlyReported() throws Exception {
		PaymentAttempt a = attempt(31L, PaymentAttemptStatus.UNKNOWN, 60);
		when(attempts.findOrphans(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(a));
		GatewayPaymentStatus charged = GatewayPaymentStatus.succeeded("ch_1", null, "succeeded");
		when(gateways.lookup(a)).thenReturn(charged);
		when(gateways.compensate(a, charged)).thenReturn(null);

		PendingOrderReconciler.OrphanResult r = reconciler.compensateOrphanAttempts();

		assertEquals(1, r.compensationNeeded);
		verify(steps, never()).recordCompensation(any(), any(), any(), any());
		verify(attempts, never()).update(any(), any(), any(), any());
		verify(metrics).compensationNeeded();
	}

	@Test
	void orphanWithoutChargeIsClosed() throws Exception {
		PaymentAttempt a = attempt(32L, PaymentAttemptStatus.INITIATED, 60);
		when(attempts.findOrphans(any(Date.class), eq(0L), anyInt())).thenReturn(Arrays.asList(a));
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.notFound());

		assertEquals(1, reconciler.compensateOrphanAttempts().closed);
		verify(attempts).update(320L, PaymentAttemptStatus.FAILED, null, PendingOrderReconciler.NOT_FOUND_REASON);
		verify(gateways, never()).compensate(any(), any());
	}

	private void set(String field, Object value) throws Exception {
		Field f = PendingOrderReconciler.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(reconciler, value);
	}
}
