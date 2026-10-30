package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderproduct.OrderProduct;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.PaymentType;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;

/** Restock, cancellation and the re-check claim from PAYMENT_REVIEW. */
class OrderCheckoutStepsLifecycleTest {

	private OrderCheckoutStepsImpl steps;
	private EntityManager em;
	private Query casUpdate;
	private Query stockUpdate;
	private TransactionService transactionService;
	private final Order order = new Order();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() throws Exception {
		steps = new OrderCheckoutStepsImpl();
		em = mock(EntityManager.class);
		transactionService = mock(TransactionService.class);
		inject("em", em);
		inject("transactionService", transactionService);

		casUpdate = mock(Query.class, RETURNS_SELF);
		stockUpdate = mock(Query.class, RETURNS_SELF);
		when(em.createQuery(startsWith("update Order o"))).thenReturn(casUpdate);
		when(em.createQuery(startsWith("update ProductAvailability"))).thenReturn(stockUpdate);

		order.setId(5L);
		order.setOrderHistory(new HashSet<>());
		order.setPaymentType(PaymentType.CREDITCARD);
		MerchantStore store = new MerchantStore();
		store.setId(1);
		order.setMerchant(store);
		OrderProduct line = new OrderProduct();
		line.setSku("SKU-1");
		line.setProductQuantity(2);
		order.setOrderProducts(new HashSet<>(Collections.singleton(line)));
		when(em.find(Order.class, 5L)).thenReturn(order);

		TypedQuery<Object[]> variants = mock(TypedQuery.class, RETURNS_SELF);
		when(variants.getResultList()).thenReturn(Collections.emptyList());
		when(em.createQuery(startsWith("select v.product.id"), eq(Object[].class))).thenReturn(variants);
		TypedQuery<Long> products = mock(TypedQuery.class, RETURNS_SELF);
		when(products.getResultList()).thenReturn(Collections.singletonList(11L));
		when(em.createQuery(startsWith("select p.id from Product"), eq(Long.class))).thenReturn(products);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = OrderCheckoutStepsImpl.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(steps, value);
	}

	@Test
	void restockReturnsEveryLine() {
		steps.restock(5L);

		verify(stockUpdate).setParameter("p", 11L);
		verify(stockUpdate).setParameter("q", 2);
		verify(stockUpdate).executeUpdate();
	}

	@Test
	void cancelClaimsOrderReturnsStockRecordsMoneyAndClosesAttempt() throws Exception {
		when(casUpdate.executeUpdate()).thenReturn(1);
		PaymentAttempt attempt = new PaymentAttempt();
		attempt.setId(50L);
		attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
		when(em.find(PaymentAttempt.class, 50L)).thenReturn(attempt);
		Transaction refund = new Transaction();
		refund.setTransactionType(TransactionType.REFUND);
		refund.setAmount(new BigDecimal("10.00"));

		assertTrue(steps.markCanceled(5L, OrderStatus.PAYMENT_PENDING, true, refund, 50L, "Canceled"));

		verify(casUpdate).setParameter("to", OrderStatus.CANCELED);
		verify(casUpdate).setParameter("from", OrderStatus.PAYMENT_PENDING);
		OrderStatusHistory h = order.getOrderHistory().iterator().next();
		assertEquals(OrderStatus.CANCELED, h.getStatus());
		assertEquals("Canceled", h.getComments());
		verify(transactionService).create(refund);
		assertEquals(PaymentType.CREDITCARD, refund.getPaymentType());
		verify(stockUpdate).executeUpdate();
		assertEquals(PaymentAttemptStatus.FAILED, attempt.getStatus());
	}

	@Test
	void cancelAfterItsOwnRefundStartsFromRefunded() throws Exception {
		when(casUpdate.executeUpdate()).thenReturn(1);

		assertTrue(steps.markCanceled(5L, OrderStatus.REFUNDED, true, null, null, "Canceled, refunded"));

		verify(casUpdate).setParameter("from", OrderStatus.REFUNDED);
		verify(stockUpdate).executeUpdate();
	}

	@Test
	void cancelOfAStatusThatCannotBeCanceledIsRejected() {
		assertThrows(IllegalOrderStatusTransitionException.class,
				() -> steps.markCanceled(5L, OrderStatus.DELIVERED, true, null, null, "x"));
		verify(casUpdate, never()).executeUpdate();
	}

	@Test
	void cancelLostToAConcurrentChangeReturnsNoStock() throws Exception {
		when(casUpdate.executeUpdate()).thenReturn(0);

		assertFalse(steps.markCanceled(5L, OrderStatus.ORDERED, true, null, null, "x"));

		verify(stockUpdate, never()).executeUpdate();
		assertTrue(order.getOrderHistory().isEmpty());
	}

	@Test
	void cancelWithoutRestockLeavesStock() throws Exception {
		when(casUpdate.executeUpdate()).thenReturn(1);

		assertTrue(steps.markCanceled(5L, OrderStatus.PAYMENT_FAILED, false, null, null, "x"));

		verify(stockUpdate, never()).executeUpdate();
	}

	@Test
	void recheckClaimsAnOrderInReview() throws Exception {
		// first compare-and-set (from PAYMENT_PENDING) misses, second (from PAYMENT_REVIEW) matches
		when(casUpdate.executeUpdate()).thenReturn(0, 1);

		assertTrue(steps.reconcilePaidOrder(5L, "reconciled: payment found", true));

		verify(casUpdate).setParameter("from", OrderStatus.PAYMENT_PENDING);
		verify(casUpdate).setParameter("from", OrderStatus.PAYMENT_REVIEW);
		verify(casUpdate, times(2)).setParameter("to", OrderStatus.PROCESSED);
	}

	@Test
	void scheduledClaimNeverTouchesAnOrderInReview() throws Exception {
		when(casUpdate.executeUpdate()).thenReturn(0);

		assertFalse(steps.reconcilePaidOrder(5L, "reconciled: payment found"));
		assertFalse(steps.reconcileUnpaidOrder(5L, "no payment"));

		verify(casUpdate, never()).setParameter("from", OrderStatus.PAYMENT_REVIEW);
	}

	@Test
	void recheckFailsAnUnpaidOrderInReviewAndReturnsStock() {
		when(casUpdate.executeUpdate()).thenReturn(0, 1);

		assertTrue(steps.reconcileUnpaidOrder(5L, "gateway reports payment failed", null, true));

		verify(casUpdate).setParameter("from", OrderStatus.PAYMENT_REVIEW);
		verify(casUpdate, times(2)).setParameter("to", OrderStatus.PAYMENT_FAILED);
		verify(stockUpdate).executeUpdate();
	}
}
