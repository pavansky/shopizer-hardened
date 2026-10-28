package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;

class OrderStatusTransitionsTest {

	@Test
	void happyPathIsAllowed() {
		assertDoesNotThrow(() -> OrderStatusTransitions.check(OrderStatus.PAYMENT_PENDING, OrderStatus.ORDERED));
		assertDoesNotThrow(() -> OrderStatusTransitions.check(OrderStatus.ORDERED, OrderStatus.PROCESSED));
		assertDoesNotThrow(() -> OrderStatusTransitions.check(OrderStatus.PROCESSED, OrderStatus.DELIVERED));
		assertDoesNotThrow(() -> OrderStatusTransitions.check(OrderStatus.DELIVERED, OrderStatus.REFUNDED));
	}

	@Test
	void illegalTransitionsAreRejected() {
		assertThrows(IllegalOrderStatusTransitionException.class,
				() -> OrderStatusTransitions.check(OrderStatus.CANCELED, OrderStatus.PROCESSED));
		assertThrows(IllegalOrderStatusTransitionException.class,
				() -> OrderStatusTransitions.check(OrderStatus.REFUNDED, OrderStatus.ORDERED));
		assertThrows(IllegalOrderStatusTransitionException.class,
				() -> OrderStatusTransitions.check(OrderStatus.PAYMENT_FAILED, OrderStatus.PROCESSED));
		assertThrows(IllegalOrderStatusTransitionException.class,
				() -> OrderStatusTransitions.check(OrderStatus.DELIVERED, OrderStatus.ORDERED));
		assertFalse(OrderStatusTransitions.isAllowed(OrderStatus.ORDERED, null));
	}

	@Test
	void everyStatusHasAnEntry() {
		for (OrderStatus s : OrderStatus.values()) {
			assertTrue(OrderStatusTransitions.allowedFrom(s) != null);
		}
	}
}
