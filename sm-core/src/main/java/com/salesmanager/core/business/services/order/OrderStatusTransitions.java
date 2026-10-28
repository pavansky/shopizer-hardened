package com.salesmanager.core.business.services.order;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;

/**
 * Explicit table of allowed order status transitions. Anything not listed is
 * rejected. Setting a status to its current value is treated as a no-op by
 * callers and is not listed here.
 */
public final class OrderStatusTransitions {

	private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

	static {
		ALLOWED.put(OrderStatus.PAYMENT_PENDING,
				EnumSet.of(OrderStatus.ORDERED, OrderStatus.PROCESSED, OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELED));
		ALLOWED.put(OrderStatus.PAYMENT_FAILED, EnumSet.of(OrderStatus.CANCELED));
		ALLOWED.put(OrderStatus.ORDERED,
				EnumSet.of(OrderStatus.PROCESSED, OrderStatus.DELIVERED, OrderStatus.CANCELED, OrderStatus.REFUNDED));
		ALLOWED.put(OrderStatus.PROCESSED,
				EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELED, OrderStatus.REFUNDED));
		ALLOWED.put(OrderStatus.DELIVERED, EnumSet.of(OrderStatus.REFUNDED));
		ALLOWED.put(OrderStatus.REFUNDED, EnumSet.noneOf(OrderStatus.class));
		ALLOWED.put(OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class));
	}

	private OrderStatusTransitions() {
	}

	public static boolean isAllowed(OrderStatus from, OrderStatus to) {
		if (to == null) {
			return false;
		}
		if (from == null) {
			// legacy rows without a status may be moved to any status
			return true;
		}
		return ALLOWED.getOrDefault(from, Collections.emptySet()).contains(to);
	}

	public static Set<OrderStatus> allowedFrom(OrderStatus from) {
		return Collections.unmodifiableSet(ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)));
	}

	/** @throws IllegalOrderStatusTransitionException when the transition is not in the table */
	public static void check(OrderStatus from, OrderStatus to) {
		if (!isAllowed(from, to)) {
			throw new IllegalOrderStatusTransitionException(from, to);
		}
	}
}
