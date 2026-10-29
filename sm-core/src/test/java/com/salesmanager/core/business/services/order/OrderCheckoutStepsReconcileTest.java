package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderproduct.OrderProduct;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.PaymentType;

class OrderCheckoutStepsReconcileTest {

	private OrderCheckoutStepsImpl steps;
	private EntityManager em;
	private Query casUpdate;
	private Query stockUpdate;
	private final Order order = new Order();

	@BeforeEach
	void setUp() throws Exception {
		steps = new OrderCheckoutStepsImpl();
		em = mock(EntityManager.class);
		Field f = OrderCheckoutStepsImpl.class.getDeclaredField("em");
		f.setAccessible(true);
		f.set(steps, em);

		casUpdate = mock(Query.class, RETURNS_SELF);
		stockUpdate = mock(Query.class, RETURNS_SELF);
		when(em.createQuery(startsWith("update Order o"))).thenReturn(casUpdate);
		when(em.createQuery(startsWith("update ProductAvailability"))).thenReturn(stockUpdate);

		order.setId(5L);
		order.setOrderHistory(new HashSet<>());
		MerchantStore store = new MerchantStore();
		store.setId(1);
		order.setMerchant(store);
		when(em.find(Order.class, 5L)).thenReturn(order);
	}

	@Test
	void paidOrderIsFinalisedAsProcessed() throws Exception {
		order.setPaymentType(PaymentType.CREDITCARD);
		when(casUpdate.executeUpdate()).thenReturn(1);

		assertTrue(steps.reconcilePaidOrder(5L, "reconciled: payment found"));

		verify(casUpdate).setParameter("to", OrderStatus.PROCESSED);
		verify(casUpdate).setParameter("from", OrderStatus.PAYMENT_PENDING);
		OrderStatusHistory h = order.getOrderHistory().iterator().next();
		assertEquals("reconciled: payment found", h.getComments());
	}

	@Test
	void paidMoneyOrderIsFinalisedAsOrdered() throws Exception {
		order.setPaymentType(PaymentType.MONEYORDER);
		when(casUpdate.executeUpdate()).thenReturn(1);

		assertTrue(steps.reconcilePaidOrder(5L, "reconciled: payment found"));

		verify(casUpdate).setParameter("to", OrderStatus.ORDERED);
	}

	@Test
	void paidOrderAlreadyClaimedIsLeftAlone() throws Exception {
		order.setPaymentType(PaymentType.CREDITCARD);
		when(casUpdate.executeUpdate()).thenReturn(0);

		assertFalse(steps.reconcilePaidOrder(5L, "reconciled: payment found"));
		assertTrue(order.getOrderHistory().isEmpty());
	}

	@SuppressWarnings("unchecked")
	@Test
	void unpaidOrderIsFailedAndStockReturned() {
		OrderProduct line = new OrderProduct();
		line.setSku("SKU-1");
		line.setProductQuantity(2);
		order.setOrderProducts(new HashSet<>(Collections.singleton(line)));
		when(casUpdate.executeUpdate()).thenReturn(1);

		TypedQuery<Object[]> variants = mock(TypedQuery.class, RETURNS_SELF);
		when(variants.getResultList()).thenReturn(Collections.emptyList());
		when(em.createQuery(startsWith("select v.product.id"), eq(Object[].class))).thenReturn(variants);
		TypedQuery<Long> products = mock(TypedQuery.class, RETURNS_SELF);
		when(products.getResultList()).thenReturn(Collections.singletonList(11L));
		when(em.createQuery(startsWith("select p.id from Product"), eq(Long.class))).thenReturn(products);

		assertTrue(steps.reconcileUnpaidOrder(5L, "reconciled: no payment recorded"));

		verify(casUpdate).setParameter("to", OrderStatus.PAYMENT_FAILED);
		verify(stockUpdate).setParameter("p", 11L);
		verify(stockUpdate).setParameter("q", 2);
		verify(stockUpdate).executeUpdate();
		assertEquals("reconciled: no payment recorded", order.getOrderHistory().iterator().next().getComments());
	}

	@Test
	void unpaidOrderClaimedElsewhereReturnsNoStock() {
		when(casUpdate.executeUpdate()).thenReturn(0);

		assertFalse(steps.reconcileUnpaidOrder(5L, "reconciled: no payment recorded"));

		verify(em, never()).createQuery(startsWith("update ProductAvailability"));
		verify(stockUpdate, never()).executeUpdate();
	}
}
