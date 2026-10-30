package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.repositories.order.OrderRepository;
import com.salesmanager.core.business.modules.integration.payment.impl.PaymentActionRequiredException;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.OrderTotalSummary;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.PaymentOutcomeUnknownException;

class OrderServiceImplCheckoutTest {

	private OrderServiceImpl service;
	private PaymentService paymentService;
	private OrderCheckoutSteps steps;
	private PaymentAttemptService attempts;
	private OrderRepository orders;

	private final Order order = new Order();
	private final Customer customer = new Customer();
	private final MerchantStore store = new MerchantStore();
	private final Payment payment = new Payment();
	private final OrderTotalSummary summary = new OrderTotalSummary();
	private final List<ShoppingCartItem> items = Collections.singletonList(new ShoppingCartItem());

	@BeforeEach
	void setUp() throws Exception {
		orders = mock(OrderRepository.class);
		service = new OrderServiceImpl(orders);
		paymentService = mock(PaymentService.class);
		steps = mock(OrderCheckoutSteps.class);
		attempts = mock(PaymentAttemptService.class);
		inject(service, "paymentService", paymentService);
		inject(service, "orderCheckoutSteps", steps);
		inject(service, "paymentAttemptService", attempts);

		when(steps.createPendingOrder(any(Order.class), eq(customer), eq(items), isNull())).thenAnswer(inv -> {
			Order o = inv.getArgument(0);
			o.setId(42L);
			o.setStatus(OrderStatus.PAYMENT_PENDING);
			return o;
		});
		when(steps.beginPaymentAttempt(any(PaymentAttempt.class))).thenAnswer(inv -> {
			PaymentAttempt a = inv.getArgument(0);
			a.setId(900L);
			return a;
		});
		store.setId(1);
		payment.setModuleName("braintree");
		order.setTotal(new BigDecimal("10.00"));
	}

	@Test
	void paymentFailureLeavesAFailedOrderAndRethrows() throws Exception {
		ServiceException declined = new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, "card declined");
		when(paymentService.processPayment(customer, store, payment, items, order)).thenThrow(declined);

		ServiceException thrown = assertThrows(ServiceException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		assertEquals(ServiceException.EXCEPTION_PAYMENT_DECLINED, thrown.getExceptionType());
		InOrder sequence = inOrder(steps, paymentService);
		sequence.verify(steps).createPendingOrder(order, customer, items, null);
		sequence.verify(paymentService).processPayment(customer, store, payment, items, order);
		sequence.verify(steps).markPaymentFailed(eq(42L), eq(items), anyString(), eq(900L));
		verify(steps, never()).markPaymentSucceeded(any(), any(), any(), any());
		verify(steps, never()).markPaymentOutcomeUnknown(any(), any());
		assertEquals(OrderStatus.PAYMENT_FAILED, order.getStatus());
	}

	@Test
	void orderIsPersistedBeforeChargeAndFinalisedAfter() throws Exception {
		Transaction tx = new Transaction();
		when(paymentService.processPayment(customer, store, payment, items, order)).thenReturn(tx);

		Order result = service.processOrder(order, customer, items, summary, payment, store);

		InOrder sequence = inOrder(steps, paymentService);
		sequence.verify(steps).createPendingOrder(order, customer, items, null);
		sequence.verify(paymentService).processPayment(customer, store, payment, items, order);
		sequence.verify(steps).markPaymentSucceeded(42L, OrderStatus.ORDERED, tx, 900L);
		verify(steps, never()).markPaymentFailed(any(), anyList(), any(), any());
		assertEquals(OrderStatus.ORDERED, result.getStatus());
	}

	@Test
	void inventoryShortfallFailsBeforeCharging() throws Exception {
		when(steps.createPendingOrder(any(Order.class), eq(customer), eq(items), isNull()))
				.thenThrow(new ServiceException(ServiceException.EXCEPTION_INVENTORY_MISMATCH, "out of stock"));

		assertThrows(ServiceException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		verify(paymentService, never()).processPayment(any(), any(), any(), any(), any());
	}

	@Test
	void chargeThatCannotBeRecordedIsReportedWithTheOrderId() throws Exception {
		when(paymentService.processPayment(customer, store, payment, items, order)).thenReturn(new Transaction());
		org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(steps).markPaymentSucceeded(any(), any(),
				any(), any());

		OrderChargedNotRecordedException thrown = assertThrows(OrderChargedNotRecordedException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));
		assertEquals(42L, thrown.getOrderId());
		verify(steps, never()).markPaymentFailed(any(), anyList(), any(), any());
	}

	@Test
	void attemptIsCommittedBeforeTheChargeAndItsReferenceGoesToTheModule() throws Exception {
		when(paymentService.processPayment(customer, store, payment, items, order)).thenAnswer(inv -> {
			// the module sees the committed attempt reference
			assertNotNull(payment.getPaymentMetaData().get(PaymentAttempt.META_ATTEMPT_REF));
			return new Transaction();
		});

		service.processOrder(order, customer, items, summary, payment, store);

		InOrder sequence = inOrder(steps, paymentService);
		sequence.verify(steps).createPendingOrder(order, customer, items, null);
		org.mockito.ArgumentCaptor<PaymentAttempt> attempt = org.mockito.ArgumentCaptor.forClass(PaymentAttempt.class);
		sequence.verify(steps).beginPaymentAttempt(attempt.capture());
		sequence.verify(paymentService).processPayment(customer, store, payment, items, order);
		assertEquals(42L, attempt.getValue().getOrderId());
		assertEquals(Integer.valueOf(1), attempt.getValue().getMerchantId());
		assertEquals("braintree", attempt.getValue().getModuleCode());
		assertEquals(new BigDecimal("10.00"), attempt.getValue().getAmount());
		assertEquals(36, attempt.getValue().getAttemptRef().length());
		assertEquals(attempt.getValue().getAttemptRef(), payment.getPaymentMetaData().get(PaymentAttempt.META_ATTEMPT_REF));
		assertNull(attempt.getValue().getGatewayRef());
	}

	@Test
	void stripePaymentIntentIdIsKnownBeforeTheCall() throws Exception {
		payment.setModuleName("stripe3");
		payment.setPaymentMetaData(new java.util.HashMap<>(java.util.Collections.singletonMap("paymentToken", "pi_123")));
		when(paymentService.processPayment(customer, store, payment, items, order)).thenReturn(new Transaction());

		service.processOrder(order, customer, items, summary, payment, store);

		org.mockito.ArgumentCaptor<PaymentAttempt> attempt = org.mockito.ArgumentCaptor.forClass(PaymentAttempt.class);
		verify(steps).beginPaymentAttempt(attempt.capture());
		assertEquals("pi_123", attempt.getValue().getGatewayRef());
	}

	@Test
	void timeoutLeavesOrderPendingAttemptUnknownAndStockHeld() throws Exception {
		when(paymentService.processPayment(customer, store, payment, items, order)).thenThrow(
				new PaymentOutcomeUnknownException("read timed out", new SocketTimeoutException("Read timed out")));

		PaymentConfirmationPendingException thrown = assertThrows(PaymentConfirmationPendingException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		assertEquals(42L, thrown.getOrderId());
		verify(steps).markPaymentOutcomeUnknown(eq(900L), anyString());
		// stock is only returned by markPaymentFailed, which must not run
		verify(steps, never()).markPaymentFailed(any(), anyList(), any(), any());
		verify(steps, never()).markPaymentSucceeded(any(), any(), any(), any());
		assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
	}

	@Test
	void unexpectedRuntimeErrorFromTheGatewayIsAnUnknownOutcome() throws Exception {
		when(paymentService.processPayment(customer, store, payment, items, order))
				.thenThrow(new IllegalStateException("connection reset"));

		assertThrows(PaymentConfirmationPendingException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		verify(steps).markPaymentOutcomeUnknown(eq(900L), anyString());
		verify(steps, never()).markPaymentFailed(any(), anyList(), any(), any());
	}

	@Test
	void gatewayDeclineFailsTheOrderAndTheAttempt() throws Exception {
		IntegrationException declined = new IntegrationException("Can't process Braintree auth + capture: declined");
		when(paymentService.processPayment(customer, store, payment, items, order)).thenThrow(declined);

		ServiceException thrown = assertThrows(ServiceException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		assertTrue(thrown == declined);
		verify(steps).markPaymentFailed(eq(42L), eq(items), anyString(), eq(900L));
		verify(steps, never()).markPaymentOutcomeUnknown(any(), any());
		assertEquals(OrderStatus.PAYMENT_FAILED, order.getStatus());
	}

	@Test
	void outcomeClassification() {
		assertTrue(OrderServiceImpl.isOutcomeUnknown(new PaymentOutcomeUnknownException("x", null)));
		assertTrue(OrderServiceImpl.isOutcomeUnknown(
				new IntegrationException("wrapped", new java.io.IOException("broken pipe"))));
		assertTrue(OrderServiceImpl.isOutcomeUnknown(new RuntimeException("unexpected")));
		assertFalse(OrderServiceImpl.isOutcomeUnknown(new IntegrationException("card declined")));
		assertFalse(OrderServiceImpl.isOutcomeUnknown(
				new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, "declined")));
	}

	@Test
	void attemptThatCannotBeRecordedFailsTheOrderWithoutCharging() throws Exception {
		when(steps.beginPaymentAttempt(any(PaymentAttempt.class))).thenThrow(new RuntimeException("db down"));

		assertThrows(ServiceException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		verify(paymentService, never()).processPayment(any(), any(), any(), any(), any());
		verify(steps).markPaymentFailed(eq(42L), eq(items), anyString(), isNull());
	}

	private void payWithIntent(String intentId) {
		payment.setModuleName("stripe3");
		payment.setPaymentMetaData(new java.util.HashMap<>(java.util.Collections.singletonMap("paymentToken", intentId)));
	}

	/** The attempt and order a first checkout left open for a 3-D Secure challenge. */
	private Order orderAwaitingChallenge(BigDecimal total) {
		PaymentAttempt waiting = new PaymentAttempt();
		waiting.setId(800L);
		waiting.setOrderId(7L);
		waiting.setMerchantId(1);
		waiting.setAttemptRef("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		waiting.setGatewayRef("pi_3ds");
		waiting.setStatus(PaymentAttemptStatus.INITIATED);
		waiting.setFailureReason(OrderServiceImpl.AWAITING_CUSTOMER_ACTION + "requires_action");
		when(attempts.findByGatewayRef("pi_3ds")).thenReturn(waiting);
		Order open = new Order();
		open.setId(7L);
		open.setStatus(OrderStatus.PAYMENT_PENDING);
		open.setTotal(total);
		when(orders.findById(7L)).thenReturn(java.util.Optional.of(open));
		return open;
	}

	@Test
	void challengeRequiredKeepsTheOrderAndAttemptOpenAndStockHeld() throws Exception {
		payWithIntent("pi_3ds");
		when(attempts.update(eq(900L), eq(PaymentAttemptStatus.INITIATED), eq("pi_3ds"), anyString())).thenReturn(true);
		when(paymentService.processPayment(customer, store, payment, items, order)).thenThrow(
				new PaymentActionRequiredException("pi_3ds", "pi_3ds_secret", "requires_action"));

		PaymentActionRequiredException thrown = assertThrows(PaymentActionRequiredException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		assertEquals(42L, thrown.getOrderId());
		assertEquals("pi_3ds_secret", thrown.getClientSecret());
		verify(attempts).update(900L, PaymentAttemptStatus.INITIATED, "pi_3ds",
				OrderServiceImpl.AWAITING_CUSTOMER_ACTION + "requires_action");
		// not a decline (stock stays reserved) and not an unknown outcome (no 202)
		verify(steps, never()).markPaymentFailed(any(), anyList(), any(), any());
		verify(steps, never()).markPaymentOutcomeUnknown(any(), any());
		verify(steps, never()).markPaymentSucceeded(any(), any(), any(), any());
		assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
	}

	@Test
	void challengeThatCannotBeRecordedFailsTheOrderAsBefore() throws Exception {
		payWithIntent("pi_3ds");
		when(attempts.update(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));
		PaymentActionRequiredException action = new PaymentActionRequiredException("pi_3ds", "s", "requires_action");
		when(paymentService.processPayment(customer, store, payment, items, order)).thenThrow(action);

		assertThrows(PaymentActionRequiredException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));

		// without the mark a retry could not find this order, so it must not stay open
		verify(steps).markPaymentFailed(eq(42L), eq(items), anyString(), eq(900L));
	}

	@Test
	void retryAfterTheChallengeResumesTheSameOrderAndAttempt() throws Exception {
		payWithIntent("pi_3ds");
		Order open = orderAwaitingChallenge(new BigDecimal("10.00"));
		Transaction captured = new Transaction();
		when(paymentService.processPayment(customer, store, payment, items, open)).thenReturn(captured);

		Order result = service.processOrder(order, customer, items, summary, payment, store);

		assertSame(open, result);
		// no second reservation, no second attempt
		verify(steps, never()).createPendingOrder(any(), any(), any(), any());
		verify(steps, never()).beginPaymentAttempt(any());
		// the capture carries the first attempt's reference as its idempotency key
		assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", payment.getPaymentMetaData().get(PaymentAttempt.META_ATTEMPT_REF));
		verify(steps).markPaymentSucceeded(7L, OrderStatus.ORDERED, captured, 800L);
		assertEquals(OrderStatus.ORDERED, result.getStatus());
	}

	@Test
	void openOrderForADifferentAmountIsRetiredBeforeANewOneIsPlaced() throws Exception {
		payWithIntent("pi_3ds");
		orderAwaitingChallenge(new BigDecimal("99.00"));
		Transaction tx = new Transaction();
		when(paymentService.processPayment(customer, store, payment, items, order)).thenReturn(tx);

		service.processOrder(order, customer, items, summary, payment, store);

		InOrder sequence = inOrder(steps);
		sequence.verify(steps).reconcileUnpaidOrder(eq(7L), anyString(), eq(800L));
		sequence.verify(steps).createPendingOrder(order, customer, items, null);
		sequence.verify(steps).markPaymentSucceeded(42L, OrderStatus.ORDERED, tx, 900L);
	}

	@Test
	void attemptThatIsNotWaitingForTheCustomerIsNotResumed() throws Exception {
		payWithIntent("pi_3ds");
		orderAwaitingChallenge(new BigDecimal("10.00"));
		attempts.findByGatewayRef("pi_3ds").setFailureReason(null);
		when(paymentService.processPayment(customer, store, payment, items, order)).thenReturn(new Transaction());

		service.processOrder(order, customer, items, summary, payment, store);

		verify(steps).createPendingOrder(order, customer, items, null);
		verify(steps, never()).reconcileUnpaidOrder(any(), any(), any());
	}

	private static void inject(Object target, String name, Object value) throws Exception {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				f.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
