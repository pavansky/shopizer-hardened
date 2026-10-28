package com.salesmanager.core.business.services.order;

import java.util.List;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;

/**
 * The committed steps of a checkout. Each method runs in its own
 * REQUIRES_NEW transaction so its outcome survives a later failure
 * (in particular a payment failure).
 */
public interface OrderCheckoutSteps {

	/**
	 * Reserves inventory with a conditional update, creates the customer if
	 * new, and inserts the order in PAYMENT_PENDING. Fails with
	 * EXCEPTION_INVENTORY_MISMATCH before anything is charged.
	 */
	Order createPendingOrder(Order order, Customer customer, List<ShoppingCartItem> items, Transaction transaction)
			throws ServiceException;

	/** Moves the order from PAYMENT_PENDING to finalStatus and stores the payment transaction. */
	void markPaymentSucceeded(Long orderId, OrderStatus finalStatus, Transaction paymentTransaction)
			throws ServiceException;

	/** Moves the order to PAYMENT_FAILED and returns the reserved inventory. */
	void markPaymentFailed(Long orderId, List<ShoppingCartItem> items, String reason);
}
