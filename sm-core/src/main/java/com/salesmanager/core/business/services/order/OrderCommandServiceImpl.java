package com.salesmanager.core.business.services.order;

import java.math.BigDecimal;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.reconciliation.PaymentGateways;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderQueries;
import com.salesmanager.core.business.services.order.reconciliation.ReconciliationMetrics;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.PaymentCompensation;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;
import com.salesmanager.core.modules.integration.payment.model.PaymentStatusLookup;

/**
 * Not transactional itself: the refund and the gateway calls each commit on
 * their own, and the final status change, stock return and history entry
 * commit together in {@link OrderCheckoutSteps#markCanceled}. The money step
 * always runs first, so an order is never CANCELED while the gateway still
 * holds money that could be returned.
 */
@Service("orderCommandService")
public class OrderCommandServiceImpl implements OrderCommandService {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderCommandServiceImpl.class);

	@Inject
	private PaymentService paymentService;

	@Inject
	private TransactionService transactionService;

	@Inject
	private PaymentAttemptService attempts;

	@Inject
	private PaymentGateways gateways;

	@Inject
	private PendingOrderQueries queries;

	@Inject
	private OrderCheckoutSteps steps;

	@Inject
	private ObjectProvider<ReconciliationMetrics> metrics;

	@Override
	public String cancel(Order order, MerchantStore store, String comment) throws ServiceException {
		OrderStatus from = order.getStatus();
		if (from == OrderStatus.CANCELED) {
			throw new OrderCommandRefusedException("Order " + order.getId() + " is already canceled");
		}
		if (from == null || !OrderStatusTransitions.isAllowed(from, OrderStatus.CANCELED)) {
			throw new OrderCommandRefusedException("Order " + order.getId() + " in status " + from
					+ " cannot be canceled" + (from == OrderStatus.DELIVERED ? "; refund it instead" : ""));
		}
		switch (from) {
		case PAYMENT_PENDING:
		case PAYMENT_REVIEW:
			return cancelUnsettled(order, from, comment);
		case PAYMENT_FAILED:
			// stock was returned when the payment failed; a late charge is handled by the orphan pass
			return finish(order, from, false, null, null, note(comment, "Canceled"));
		default:
			return cancelSettled(order, store, from, comment);
		}
	}

	/** ORDERED or PROCESSED: refund what was captured, or void an authorisation. */
	private String cancelSettled(Order order, MerchantStore store, OrderStatus from, String comment)
			throws ServiceException {
		BigDecimal remaining = transactionService.getRefundableAmount(order);
		if (transactionService.getRefundableTransaction(order) != null && remaining.signum() > 0) {
			// Moves the order to REFUNDED and records the REFUND; the cancel completes from there.
			try {
				paymentService.processRefund(order, null, store, remaining);
			} catch (ServiceException e) {
				if (e.getExceptionType() == ServiceException.EXCEPTION_VALIDATION) {
					// processRefund re-reads the refundable amount under the order lock: a
					// concurrent cancel or refund already returned the money
					throw new OrderCommandRefusedException("Order " + order.getId()
							+ " changed while it was being canceled: " + e.getMessage());
				}
				throw e;
			}
			return finish(order, OrderStatus.REFUNDED, true, null, null,
					note(comment, "Canceled, refunded " + amount(remaining, order)));
		}
		if (transactionService.getCapturableTransaction(order) != null) {
			return voidAndCancel(order, from, comment);
		}
		return finish(order, from, true, null, null, note(comment, "Canceled, no payment to return"));
	}

	private String voidAndCancel(Order order, OrderStatus from, String comment) throws ServiceException {
		PaymentAttempt attempt = attempts.latestForOrder(order.getId());
		if (attempt == null) {
			LOGGER.warn("Order {} canceled with an authorisation that has no payment attempt; it is not voided"
					+ " and expires at the gateway", order.getId());
			return finish(order, from, true, null, null,
					note(comment, "Canceled; the authorisation could not be voided automatically and expires at the gateway"));
		}
		GatewayPaymentStatus status = lookup(attempt);
		if (status.getOutcome() != GatewayPaymentStatus.Outcome.SUCCEEDED) {
			return finish(order, from, true, null, null,
					note(comment, "Canceled; the gateway reports no open authorisation (" + status.getOutcome() + ")"));
		}
		Transaction voided = compensate(attempt, status);
		if (voided == null) {
			LOGGER.warn("Order {} canceled; module {} cannot void authorisation {}", order.getId(),
					attempt.getModuleCode(), status.getGatewayTransactionId());
			return finish(order, from, true, null, null,
					note(comment, "Canceled; the authorisation could not be voided automatically and expires at the gateway"));
		}
		return finish(order, from, true, voided, null, note(comment, "Canceled, authorisation voided"));
	}

	/**
	 * PAYMENT_PENDING or PAYMENT_REVIEW. A payment the shop already recorded is
	 * settled first with a re-check, so the refund goes through the normal path.
	 * Otherwise the gateway is asked, and a charge it took is returned now.
	 */
	private String cancelUnsettled(Order order, OrderStatus from, String comment) throws ServiceException {
		Long orderId = order.getId();
		if (queries.hasSuccessfulPayment(orderId) || attempts.hasSucceeded(orderId)) {
			throw new OrderCommandRefusedException("Order " + orderId + " has a recorded payment; re-check the"
					+ " payment first so the order is settled, then cancel it to refund the payment");
		}
		PaymentAttempt attempt = attempts.latestForOrder(orderId);
		if (attempt == null || attempt.getStatus() == PaymentAttemptStatus.FAILED
				|| attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
			return finish(order, from, true, null, null, note(comment, "Canceled, no payment taken"));
		}
		GatewayPaymentStatus status = lookup(attempt);
		switch (status.getOutcome()) {
		case SUCCEEDED:
			Transaction returned = compensate(attempt, status);
			if (returned == null) {
				// No module with a status lookup lacks compensation today; if one does, the
				// attempt stays open and the orphan pass raises it for a manual refund.
				LOGGER.error("Order {} canceled but module {} cannot return charge {}; refund it by hand",
						orderId, attempt.getModuleCode(), status.getGatewayTransactionId());
				return finish(order, from, true, null, null, note(comment,
						"Canceled; the gateway charge could not be returned automatically and must be refunded by hand"));
			}
			return finishAfterReturn(order, from, returned, attempt.getId(),
					note(comment, "Canceled, gateway charge returned"));
		case FAILED:
			return finish(order, from, true, null, attempt.getId(), note(comment, "Canceled, the payment had failed"));
		default:
			// Not final or not visible at the gateway yet. The attempt stays open either way.
			if (lateChargeIsReturnedAutomatically(attempt, status)) {
				// the orphan pass keeps asking and returns the money if the charge went through
				return finish(order, from, true, null, null, note(comment,
						"Canceled; payment outcome not confirmed (" + status.getOutcome()
								+ "), any late charge is returned automatically"));
			}
			// Nobody will ever get an answer for this attempt (a module without a status
			// lookup or compensation, or a charge the module cannot search for), so a
			// person has to check the gateway.
			LOGGER.error("Order {} canceled with payment attempt {} of module {} in an unknown state ({}); the"
					+ " shop cannot ask the gateway. Check the gateway and refund by hand if the customer was charged.",
					orderId, attempt.getAttemptRef(), attempt.getModuleCode(), status);
			if (metrics != null) {
				metrics.ifAvailable(ReconciliationMetrics::compensationNeeded);
			}
			return finish(order, from, true, null, null, note(comment,
					"Canceled; the payment outcome is unknown and cannot be checked automatically (" + status
							+ "). Any charge is checked at the payment provider and refunded by hand"));
		}
	}

	/**
	 * True when the reconciler's orphan pass can settle this attempt later: the
	 * module can both look up and return a charge, and the lookup actually found
	 * something to follow (a gateway id) or reported that nothing exists yet.
	 * A PENDING answer without a gateway id is what a module gives when it cannot
	 * look the payment up at all, for example a Stripe charge made without an
	 * attempt reference.
	 */
	private boolean lateChargeIsReturnedAutomatically(PaymentAttempt attempt, GatewayPaymentStatus status)
			throws ServiceException {
		PaymentModule module = paymentService.getPaymentModule(attempt.getModuleCode());
		if (!(module instanceof PaymentStatusLookup) || !(module instanceof PaymentCompensation)) {
			return false;
		}
		return status.getOutcome() == GatewayPaymentStatus.Outcome.NOT_FOUND
				|| StringUtils.isNotBlank(status.getGatewayTransactionId());
	}

	/**
	 * The charge was already given back. If the order changed in the meantime (the
	 * reconciler settled it), the refund is still recorded on the order before the
	 * command is refused, so the ledger matches the gateway.
	 */
	private String finishAfterReturn(Order order, OrderStatus from, Transaction returned, Long attemptId,
			String note) throws ServiceException {
		if (steps.markCanceled(order.getId(), from, true, returned, attemptId, note)) {
			return note;
		}
		steps.recordCompensation(order.getId(), attemptId, returned,
				"charge returned by a cancel that lost a race with payment settlement");
		throw new OrderCommandRefusedException("Order " + order.getId()
				+ " changed while it was being canceled; the gateway charge was returned and recorded");
	}

	private String finish(Order order, OrderStatus from, boolean restock, Transaction returned, Long attemptToClose,
			String note) throws ServiceException {
		if (!steps.markCanceled(order.getId(), from, restock, returned, attemptToClose, note)) {
			throw new OrderCommandRefusedException("Order " + order.getId() + " changed while it was being canceled");
		}
		return note;
	}

	private GatewayPaymentStatus lookup(PaymentAttempt attempt) throws ServiceException {
		try {
			return gateways.lookup(attempt);
		} catch (Exception e) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR,
					"Could not ask the gateway about payment attempt " + attempt.getAttemptRef() + ": " + e.getMessage());
		}
	}

	private Transaction compensate(PaymentAttempt attempt, GatewayPaymentStatus status) throws ServiceException {
		try {
			return gateways.compensate(attempt, status);
		} catch (Exception e) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR, "The gateway did not return payment "
					+ status.getGatewayTransactionId() + ": " + e.getMessage());
		}
	}

	private static String note(String comment, String what) {
		return StringUtils.isBlank(comment) ? what : what + ". " + comment.trim();
	}

	private static String amount(BigDecimal value, Order order) {
		String currency = order.getCurrency() == null ? "" : " " + order.getCurrency().getCode();
		return value.toPlainString() + currency;
	}
}
