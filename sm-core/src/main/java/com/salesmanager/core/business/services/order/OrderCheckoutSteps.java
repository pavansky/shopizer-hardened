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
	 * Same as {@link #reconcilePaidOrder(Long, String)}. With fromReview the
	 * order may also be claimed from PAYMENT_REVIEW; only an explicit re-check
	 * requested by a person passes true.
	 */
	boolean reconcilePaidOrder(Long orderId, String comment, boolean fromReview) throws ServiceException;

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
	 * Same as {@link #reconcileUnpaidOrder(Long, String, Long)} (attemptId may
	 * be null). With fromReview the order may also be claimed from
	 * PAYMENT_REVIEW.
	 */
	boolean reconcileUnpaidOrder(Long orderId, String reason, Long attemptId, boolean fromReview);

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
	 * Same as {@link #reconcileConfirmedPayment(Long, Long, Transaction, String)}.
	 * With fromReview the order may also be claimed from PAYMENT_REVIEW.
	 */
	boolean reconcileConfirmedPayment(Long orderId, Long attemptId, Transaction paymentTransaction, String comment,
			boolean fromReview) throws ServiceException;

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

	/**
	 * Returns the stock of every line of the order to inventory, with the same
	 * update the checkout uses when a payment fails. Lines whose SKU no longer
	 * resolves to a product of the order's store are skipped.
	 */
	void restock(Long orderId);

	/**
	 * Final step of a cancellation. Claims the order with a compare-and-set
	 * from -> CANCELED, stores the transaction that gave money back (a refund
	 * or a voided authorisation, may be null), returns the stock when asked,
	 * closes the given payment attempt (may be null) and writes the history
	 * entry, all in one transaction.
	 *
	 * from is the status read before the cancellation started, or REFUNDED when
	 * the cancellation itself refunded the captured amount just before.
	 *
	 * @return false when the order is no longer in status from
	 */
	boolean markCanceled(Long orderId, OrderStatus from, boolean restock, Transaction moneyReturned,
			Long attemptToClose, String comment) throws ServiceException;

	/**
	 * A plain status change (fulfilment: ORDERED, PROCESSED, DELIVERED). Claims
	 * the order with a compare-and-set from -> to and writes the history entry
	 * in the same transaction, so a change that raced with a cancel or a refund
	 * cannot overwrite it.
	 *
	 * @return false when the order is no longer in status from
	 * @throws IllegalOrderStatusTransitionException from -> to is not in the
	 *         transition table
	 */
	boolean markStatus(Long orderId, OrderStatus from, OrderStatus to, String comment, boolean customerNotified);
}
