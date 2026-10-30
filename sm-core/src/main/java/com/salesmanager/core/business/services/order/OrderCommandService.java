package com.salesmanager.core.business.services.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;

/**
 * Order status changes that move money or stock. A plain status update only
 * covers fulfilment; cancelling goes through here so the payment and the
 * inventory follow the status.
 */
public interface OrderCommandService {

	/**
	 * Targets a plain status update may set. CANCELED and REFUNDED move money and
	 * have their own commands; the PAYMENT_* states are set by checkout and by
	 * payment reconciliation only.
	 */
	Set<OrderStatus> STATUS_UPDATE_TARGETS = Collections
			.unmodifiableSet(EnumSet.of(OrderStatus.ORDERED, OrderStatus.PROCESSED, OrderStatus.DELIVERED));

	/**
	 * Sources a plain status update may move from: an order whose payment is
	 * settled. A PAYMENT_PENDING or PAYMENT_REVIEW order has no recorded payment,
	 * so marking it ORDERED or PROCESSED by hand would ship goods nobody paid for
	 * and leave its payment attempt open; it goes through the payment re-check
	 * or cancel instead.
	 */
	Set<OrderStatus> STATUS_UPDATE_SOURCES = Collections
			.unmodifiableSet(EnumSet.of(OrderStatus.ORDERED, OrderStatus.PROCESSED, OrderStatus.DELIVERED));

	/** Statuses a plain status update can move this status to. */
	static Set<OrderStatus> statusUpdatesFrom(OrderStatus current) {
		Set<OrderStatus> next = EnumSet.noneOf(OrderStatus.class);
		if (!STATUS_UPDATE_SOURCES.contains(current)) {
			return next;
		}
		for (OrderStatus s : OrderStatusTransitions.allowedFrom(current)) {
			if (STATUS_UPDATE_TARGETS.contains(s)) {
				next.add(s);
			}
		}
		return next;
	}

	/**
	 * Cancels the order and gives the money back first:
	 * <ul>
	 * <li>captured money still refundable is refunded through
	 * PaymentService.processRefund;</li>
	 * <li>an authorisation that was never captured is voided at the gateway;</li>
	 * <li>a PAYMENT_PENDING or PAYMENT_REVIEW order is checked with the gateway,
	 * and a charge the gateway did take is returned before the order is
	 * cancelled. While the outcome is still unknown the attempt stays open, and
	 * the reconciler's orphan pass returns a late charge.</li>
	 * </ul>
	 * The reserved stock is returned (not for PAYMENT_FAILED orders, whose stock
	 * was already returned) and the order moves to CANCELED with a history
	 * entry.
	 *
	 * @return a short description of what was done, used for the history and
	 *         the customer email
	 * @throws OrderCommandRefusedException the order cannot be cancelled in its
	 *         current state, or changed while it was being cancelled
	 * @throws ServiceException the gateway could not be reached or refused;
	 *         nothing was cancelled
	 */
	String cancel(Order order, MerchantStore store, String comment) throws ServiceException;
}
