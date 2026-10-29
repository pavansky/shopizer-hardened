package com.salesmanager.core.business.services.order;

import java.util.List;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.PaymentAttempt;
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

	/**
	 * Commits the payment attempt in INITIATED before the gateway is called, so
	 * a crash after the charge still leaves a reference to look up.
	 */
	PaymentAttempt beginPaymentAttempt(PaymentAttempt attempt);

	/**
	 * Moves the order from PAYMENT_PENDING to finalStatus, stores the payment
	 * transaction and marks the attempt SUCCEEDED, all in one transaction.
	 */
	void markPaymentSucceeded(Long orderId, OrderStatus finalStatus, Transaction paymentTransaction, Long attemptId)
			throws ServiceException;

	/**
	 * Moves the order to PAYMENT_FAILED, returns the reserved inventory and
	 * marks the attempt FAILED. Only for a definitive decline.
	 */
	void markPaymentFailed(Long orderId, List<ShoppingCartItem> items, String reason, Long attemptId);

	/**
	 * The gateway call ended without a clear answer. Marks the attempt UNKNOWN
	 * and leaves the order in PAYMENT_PENDING with its stock held.
	 */
	void markPaymentOutcomeUnknown(Long attemptId, String reason);

	/**
	 * Reconciliation of an order left in PAYMENT_PENDING for which a successful
	 * payment transaction is already stored. Claims the order with a
	 * compare-and-set PAYMENT_PENDING -> ORDERED (money order) or PROCESSED
	 * (other payment types) and records the reconciliation in the history.
	 *
	 * @return false when the order is no longer PAYMENT_PENDING (another
	 *         instance or the original request got there first)
	 */
	boolean reconcilePaidOrder(Long orderId, String comment) throws ServiceException;

	/**
	 * Reconciliation of an order left in PAYMENT_PENDING without any recorded
	 * payment. Claims the order with a compare-and-set PAYMENT_PENDING ->
	 * PAYMENT_FAILED and returns the inventory reserved for its lines.
	 *
	 * @return false when the order is no longer PAYMENT_PENDING
	 */
	boolean reconcileUnpaidOrder(Long orderId, String reason);

	/**
	 * Same as {@link #reconcileUnpaidOrder(Long, String)} and also marks the
	 * attempt FAILED in the same transaction.
	 */
	boolean reconcileUnpaidOrder(Long orderId, String reason, Long attemptId);

	/**
	 * The gateway confirmed a payment the shop never recorded. Claims the order
	 * (PAYMENT_PENDING -> ORDERED or PROCESSED), stores the payment transaction,
	 * marks the attempt SUCCEEDED and writes the history entry.
	 *
	 * @return false when the order is no longer PAYMENT_PENDING
	 */
	boolean reconcileConfirmedPayment(Long orderId, Long attemptId, Transaction paymentTransaction, String comment)
			throws ServiceException;

	/**
	 * Moves an order whose payment outcome is still unknown from
	 * PAYMENT_PENDING to PAYMENT_REVIEW. Stock stays reserved.
	 *
	 * @return false when the order is no longer PAYMENT_PENDING
	 */
	boolean moveToPaymentReview(Long orderId, String comment);

	/**
	 * A late charge on a failed or cancelled order was given back. Stores the
	 * REFUND transaction, writes the history entry and marks the attempt FAILED.
	 */
	void recordCompensation(Long orderId, Long attemptId, Transaction refundTransaction, String comment)
			throws ServiceException;
}
