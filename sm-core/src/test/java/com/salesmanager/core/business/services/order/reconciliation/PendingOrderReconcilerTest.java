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

class PendingOrderReconcilerTest {

	private OrderCheckoutSteps steps;
	private PendingOrderQueries queries;
	private OrderIdempotencyService idempotency;
	private ReconciliationMetrics metrics;
	private PendingOrderReconciler reconciler;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		steps = mock(OrderCheckoutSteps.class);
		queries = mock(PendingOrderQueries.class);
		idempotency = mock(OrderIdempotencyService.class);
		metrics = mock(ReconciliationMetrics.class);
		ObjectProvider<ReconciliationMetrics> provider = mock(ObjectProvider.class);
		org.mockito.Mockito.doAnswer(inv -> {
			((java.util.function.Consumer<ReconciliationMetrics>) inv.getArgument(0)).accept(metrics);
			return null;
		}).when(provider).ifAvailable(any());
		reconciler = new PendingOrderReconciler(steps, queries, idempotency, provider);
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

	private void set(String field, Object value) throws Exception {
		Field f = PendingOrderReconciler.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(reconciler, value);
	}
}
