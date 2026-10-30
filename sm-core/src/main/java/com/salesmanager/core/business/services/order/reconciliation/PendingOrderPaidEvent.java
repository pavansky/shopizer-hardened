package com.salesmanager.core.business.services.order.reconciliation;

/**
 * Published when the reconciler settles an order that was still
 * PAYMENT_PENDING (or PAYMENT_REVIEW on an explicit re-check) as paid. The
 * checkout request had already ended without sending the order confirmation,
 * so a listener in the shop sends it now.
 */
public class PendingOrderPaidEvent {

	private final Long orderId;

	public PendingOrderPaidEvent(Long orderId) {
		this.orderId = orderId;
	}

	public Long getOrderId() {
		return orderId;
	}
}
