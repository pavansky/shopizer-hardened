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
	 * cancelled. While the outcome is still unknown the attempt stays open; when
	 * the module can look the payment up and return it, the reconciler's orphan
	 * pass returns a late charge, otherwise the history says the charge must be
	 * checked and refunded by hand and the case is logged and counted as
	 * compensation needed.</li>
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

	/**
	 * A person confirms a payment the gateway cannot report, for example a money
	 * order that arrived or a charge seen in the gateway dashboard that the shop
	 * cannot look up. Only for PAYMENT_REVIEW orders: a PAYMENT_PENDING order is
	 * still being settled by the reconciler and is re-checked instead.
	 *
	 * Records a captured payment for the order total, closes the payment
	 * attempt as succeeded, moves the order to ORDERED (money order) or
	 * PROCESSED with a history entry naming the person, the reason and the
	 * reference, and publishes the event that sends the order confirmation.
	 *
	 * @param confirmedBy the admin user who confirmed the payment
	 * @param reference the gateway or bank reference of the payment, may be blank
	 * @param reason why the payment is known to be received, required
	 * @return the history text
	 * @throws OrderCommandRefusedException the order is not in PAYMENT_REVIEW,
	 *         already has a recorded payment, or changed meanwhile
	 */
	String confirmPayment(Order order, String confirmedBy, String reference, String reason) throws ServiceException;
}
