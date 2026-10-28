package com.salesmanager.core.business.services.order;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;

/** Raised when an order status change is not in {@link OrderStatusTransitions}. Maps to HTTP 409. */
public class IllegalOrderStatusTransitionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final OrderStatus from;
	private final OrderStatus to;

	public IllegalOrderStatusTransitionException(OrderStatus from, OrderStatus to) {
		super("Order status transition " + from + " -> " + to + " is not allowed");
		this.from = from;
		this.to = to;
	}

	public OrderStatus getFrom() { return from; }
	public OrderStatus getTo() { return to; }
}
