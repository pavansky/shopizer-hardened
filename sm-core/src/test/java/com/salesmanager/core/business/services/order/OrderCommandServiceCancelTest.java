package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.reconciliation.PaymentGateways;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderQueries;
import com.salesmanager.core.business.services.order.reconciliation.ReconciliationMetrics;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.PaymentCompensation;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;
import com.salesmanager.core.modules.integration.payment.model.PaymentStatusLookup;

class OrderCommandServiceCancelTest {

	private OrderCommandServiceImpl service;
	private PaymentService paymentService;
	private TransactionService transactionService;
	private PaymentAttemptService attempts;
	private PaymentGateways gateways;
	private PendingOrderQueries queries;
	private OrderCheckoutSteps steps;
	private ObjectProvider<ReconciliationMetrics> metrics;
	private final MerchantStore store = new MerchantStore();
	private final Order order = new Order();

	@BeforeEach
	void setUp() throws Exception {
		service = new OrderCommandServiceImpl();
		paymentService = mock(PaymentService.class);
		transactionService = mock(TransactionService.class);
		attempts = mock(PaymentAttemptService.class);
		gateways = mock(PaymentGateways.class);
		queries = mock(PendingOrderQueries.class);
		steps = mock(OrderCheckoutSteps.class);
		inject("paymentService", paymentService);
		inject("transactionService", transactionService);
		inject("attempts", attempts);
		inject("gateways", gateways);
		inject("queries", queries);
		inject("steps", steps);
		@SuppressWarnings("unchecked")
		ObjectProvider<ReconciliationMetrics> provider = mock(ObjectProvider.class);
		metrics = provider;
		inject("metrics", metrics);
		store.setId(1);
		order.setId(9L);
		when(transactionService.getRefundableAmount(order)).thenReturn(BigDecimal.ZERO);
		when(steps.markCanceled(anyLong(), any(), anyBoolean(), any(), any(), anyString())).thenReturn(true);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = OrderCommandServiceImpl.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(service, value);
	}

	private static PaymentAttempt attempt(PaymentAttemptStatus status) {
		PaymentAttempt a = new PaymentAttempt();
		a.setId(90L);
		a.setOrderId(9L);
		a.setAttemptRef("ref-9");
		a.setModuleCode("stripe3");
		a.setStatus(status);
		return a;
	}

	@Test
	void cancelingACanceledOrderIsRefused() {
		order.setStatus(OrderStatus.CANCELED);

		assertThrows(OrderCommandRefusedException.class, () -> service.cancel(order, store, null));
		verifyNoInteractions(paymentService, steps, gateways);
	}

	@Test
	void deliveredOrderIsRefundedNotCanceled() {
		order.setStatus(OrderStatus.DELIVERED);

		OrderCommandRefusedException e = assertThrows(OrderCommandRefusedException.class,
				() -> service.cancel(order, store, null));
		assertTrue(e.getMessage().contains("refund it instead"));
		verifyNoInteractions(paymentService, steps);
	}

	@Test
	void capturedMoneyIsRefundedThenOrderCanceledWithStock() throws Exception {
		order.setStatus(OrderStatus.PROCESSED);
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("149.00"));

		String done = service.cancel(order, store, "customer asked");

		InOrder sequence = inOrder(paymentService, steps);
		sequence.verify(paymentService).processRefund(order, null, store, new BigDecimal("149.00"));
		sequence.verify(steps).markCanceled(eq(9L), eq(OrderStatus.REFUNDED), eq(true), isNull(), isNull(),
				anyString());
		assertTrue(done.contains("refunded 149.00"));
		assertTrue(done.contains("customer asked"));
		verifyNoInteractions(gateways);
	}

	@Test
	void authorisationOnlyIsVoided() throws Exception {
		order.setStatus(OrderStatus.ORDERED);
		when(transactionService.getCapturableTransaction(order)).thenReturn(new Transaction());
		PaymentAttempt a = attempt(PaymentAttemptStatus.SUCCEEDED);
		when(attempts.latestForOrder(9L)).thenReturn(a);
		GatewayPaymentStatus authorised = GatewayPaymentStatus.authorized("pi_1", new BigDecimal("10.00"), "requires_capture");
		when(gateways.lookup(a)).thenReturn(authorised);
		Transaction voided = new Transaction();
		when(gateways.compensate(a, authorised)).thenReturn(voided);

		service.cancel(order, store, null);

		verify(steps).markCanceled(eq(9L), eq(OrderStatus.ORDERED), eq(true), eq(voided), isNull(), anyString());
		verify(paymentService, never()).processRefund(any(), any(), any(), any());
	}

	@Test
	void orderWithoutPaymentIsCanceledWithStock() throws Exception {
		order.setStatus(OrderStatus.ORDERED);

		service.cancel(order, store, null);

		verify(steps).markCanceled(eq(9L), eq(OrderStatus.ORDERED), eq(true), isNull(), isNull(), anyString());
		verifyNoInteractions(gateways, paymentService);
	}

	@Test
	void pendingOrderChargedAtTheGatewayIsReturnedBeforeCancel() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		PaymentAttempt a = attempt(PaymentAttemptStatus.UNKNOWN);
		when(attempts.latestForOrder(9L)).thenReturn(a);
		GatewayPaymentStatus charged = GatewayPaymentStatus.succeeded("pi_2", new BigDecimal("10.00"), "succeeded");
		when(gateways.lookup(a)).thenReturn(charged);
		Transaction refund = new Transaction();
		when(gateways.compensate(a, charged)).thenReturn(refund);

		service.cancel(order, store, null);

		InOrder sequence = inOrder(gateways, steps);
		sequence.verify(gateways).compensate(a, charged);
		sequence.verify(steps).markCanceled(eq(9L), eq(OrderStatus.PAYMENT_PENDING), eq(true), eq(refund), eq(90L),
				anyString());
		verify(paymentService, never()).processRefund(any(), any(), any(), any());
	}

	private PaymentModule moduleWithLookupAndCompensation() {
		return mock(PaymentModule.class,
				withSettings().extraInterfaces(PaymentStatusLookup.class, PaymentCompensation.class));
	}

	@Test
	void pendingChargeTheGatewayKnowsIsLeftToTheOrphanPass() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_REVIEW);
		PaymentAttempt a = attempt(PaymentAttemptStatus.UNKNOWN);
		when(attempts.latestForOrder(9L)).thenReturn(a);
		PaymentModule stripe = moduleWithLookupAndCompensation();
		when(paymentService.getPaymentModule("stripe3")).thenReturn(stripe);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.pending("pi_4", "processing"));

		String done = service.cancel(order, store, null);

		verify(gateways, never()).compensate(any(), any());
		// attempt left open (null): the orphan pass keeps asking and returns a late charge
		verify(steps).markCanceled(eq(9L), eq(OrderStatus.PAYMENT_REVIEW), eq(true), isNull(), isNull(), anyString());
		assertTrue(done.contains("returned automatically"), done);
		verify(metrics, never()).ifAvailable(any());
	}

	@Test
	void unknownOutcomeOfAModuleWithoutLookupAsksForAManualCheck() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_REVIEW);
		PaymentAttempt a = attempt(PaymentAttemptStatus.UNKNOWN);
		a.setModuleCode("moneyorder");
		when(attempts.latestForOrder(9L)).thenReturn(a);
		when(paymentService.getPaymentModule("moneyorder")).thenReturn(mock(PaymentModule.class));
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.pending(null, "module moneyorder has no status lookup"));

		String done = service.cancel(order, store, null);

		verify(steps).markCanceled(eq(9L), eq(OrderStatus.PAYMENT_REVIEW), eq(true), isNull(), isNull(), anyString());
		assertTrue(done.contains("refunded by hand"), done);
		assertFalse(done.contains("returned automatically"), done);
		verify(metrics).ifAvailable(any());
	}

	@Test
	void chargeTheModuleCannotSearchForAsksForAManualCheck() throws Exception {
		// a Stripe charge made without an attempt reference: lookup answers PENDING without an id
		order.setStatus(OrderStatus.PAYMENT_REVIEW);
		PaymentAttempt a = attempt(PaymentAttemptStatus.UNKNOWN);
		when(attempts.latestForOrder(9L)).thenReturn(a);
		PaymentModule stripe = moduleWithLookupAndCompensation();
		when(paymentService.getPaymentModule("stripe3")).thenReturn(stripe);
		when(gateways.lookup(a)).thenReturn(GatewayPaymentStatus.pending(null, "no attempt reference to search on"));

		String done = service.cancel(order, store, null);

		assertTrue(done.contains("refunded by hand"), done);
		verify(metrics).ifAvailable(any());
	}

	@Test
	void secondConcurrentCancelOfACapturedOrderIsRefusedNotAPaymentError() throws Exception {
		order.setStatus(OrderStatus.ORDERED);
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("149.00"));
		// under the order lock processRefund finds nothing left: the first cancel refunded it
		when(paymentService.processRefund(order, null, store, new BigDecimal("149.00"))).thenThrow(
				new ServiceException(ServiceException.EXCEPTION_VALIDATION, "Invalid refund amount 149.00"));

		assertThrows(OrderCommandRefusedException.class, () -> service.cancel(order, store, null));
		verify(steps, never()).markCanceled(anyLong(), any(), anyBoolean(), any(), any(), anyString());
	}

	@Test
	void gatewayRefundFailureStaysAPaymentError() throws Exception {
		order.setStatus(OrderStatus.ORDERED);
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("149.00"));
		when(paymentService.processRefund(order, null, store, new BigDecimal("149.00")))
				.thenThrow(new ServiceException(ServiceException.EXCEPTION_ERROR, "gateway down"));

		ServiceException e = assertThrows(ServiceException.class, () -> service.cancel(order, store, null));
		assertEquals("gateway down", e.getMessage());
	}

	@Test
	void pendingOrderWithARecordedPaymentMustBeRecheckedFirst() {
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		when(queries.hasSuccessfulPayment(9L)).thenReturn(true);

		assertThrows(OrderCommandRefusedException.class, () -> service.cancel(order, store, null));
		verifyNoInteractions(gateways, steps, paymentService);
	}

	@Test
	void unreachableGatewayCancelsNothing() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		PaymentAttempt a = attempt(PaymentAttemptStatus.INITIATED);
		when(attempts.latestForOrder(9L)).thenReturn(a);
		when(gateways.lookup(a)).thenThrow(new java.io.IOException("timeout"));

		assertThrows(ServiceException.class, () -> service.cancel(order, store, null));
		verifyNoInteractions(steps);
	}

	@Test
	void failedPaymentOrderIsCanceledWithoutReturningStockTwice() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_FAILED);

		service.cancel(order, store, null);

		verify(steps).markCanceled(eq(9L), eq(OrderStatus.PAYMENT_FAILED), eq(false), isNull(), isNull(), anyString());
		verifyNoInteractions(gateways, paymentService);
	}

	@Test
	void concurrentChangeIsRefused() throws Exception {
		order.setStatus(OrderStatus.ORDERED);
		when(steps.markCanceled(anyLong(), any(), anyBoolean(), any(), any(), anyString())).thenReturn(false);

		assertThrows(OrderCommandRefusedException.class, () -> service.cancel(order, store, null));
	}

	@Test
	void returnedChargeIsRecordedEvenWhenTheCancelLosesARace() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		PaymentAttempt a = attempt(PaymentAttemptStatus.UNKNOWN);
		when(attempts.latestForOrder(9L)).thenReturn(a);
		GatewayPaymentStatus charged = GatewayPaymentStatus.succeeded("pi_3", new BigDecimal("10.00"), "succeeded");
		when(gateways.lookup(a)).thenReturn(charged);
		Transaction refund = new Transaction();
		when(gateways.compensate(a, charged)).thenReturn(refund);
		when(steps.markCanceled(anyLong(), any(), anyBoolean(), any(), any(), anyString())).thenReturn(false);

		assertThrows(OrderCommandRefusedException.class, () -> service.cancel(order, store, null));
		verify(steps).recordCompensation(eq(9L), eq(90L), eq(refund), anyString());
	}

	@Test
	void statusUpdatesCoverFulfilmentOnly() {
		assertTrue(OrderCommandService.statusUpdatesFrom(OrderStatus.ORDERED)
				.equals(java.util.EnumSet.of(OrderStatus.PROCESSED, OrderStatus.DELIVERED)));
		assertTrue(OrderCommandService.statusUpdatesFrom(OrderStatus.DELIVERED).isEmpty());
		assertTrue(OrderCommandService.statusUpdatesFrom(OrderStatus.PROCESSED)
				.equals(java.util.EnumSet.of(OrderStatus.DELIVERED)));
		// no payment recorded yet: only the re-check or cancel can move these
		assertTrue(OrderCommandService.statusUpdatesFrom(OrderStatus.PAYMENT_PENDING).isEmpty());
		assertTrue(OrderCommandService.statusUpdatesFrom(OrderStatus.PAYMENT_REVIEW).isEmpty());
		assertTrue(OrderCommandService.statusUpdatesFrom(OrderStatus.PAYMENT_FAILED).isEmpty());
		assertTrue(OrderCommandService.statusUpdatesFrom(null).isEmpty());
	}
}
