package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.repositories.order.OrderRepository;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.OrderTotalSummary;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;

class OrderServiceImplCheckoutTest {

	private OrderServiceImpl service;
	private PaymentService paymentService;
	private OrderCheckoutSteps steps;

	private final Order order = new Order();
	private final Customer customer = new Customer();
	private final MerchantStore store = new MerchantStore();
	private final Payment payment = new Payment();
	private final OrderTotalSummary summary = new OrderTotalSummary();
	private final List<ShoppingCartItem> items = Collections.singletonList(new ShoppingCartItem());

	@BeforeEach
	void setUp() throws Exception {
		service = new OrderServiceImpl(mock(OrderRepository.class));
		paymentService = mock(PaymentService.class);
		steps = mock(OrderCheckoutSteps.class);
		inject(service, "paymentService", paymentService);
		inject(service, "orderCheckoutSteps", steps);

		when(steps.createPendingOrder(any(Order.class), eq(customer), eq(items), isNull())).thenAnswer(inv -> {
			Order o = inv.getArgument(0);
			o.setId(42L);
			o.setStatus(OrderStatus.PAYMENT_PENDING);
			return o;
		});
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
		sequence.verify(steps).markPaymentFailed(eq(42L), eq(items), anyString());
		verify(steps, never()).markPaymentSucceeded(any(), any(), any());
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
		sequence.verify(steps).markPaymentSucceeded(42L, OrderStatus.ORDERED, tx);
		verify(steps, never()).markPaymentFailed(any(), anyList(), any());
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
				any());

		OrderChargedNotRecordedException thrown = assertThrows(OrderChargedNotRecordedException.class,
				() -> service.processOrder(order, customer, items, summary, payment, store));
		assertEquals(42L, thrown.getOrderId());
		verify(steps, never()).markPaymentFailed(any(), anyList(), any());
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
