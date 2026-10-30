package com.salesmanager.core.business.services.payments;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.OrderStatusTransitions;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;

/**
 * Records what a payment gateway reports on its own (refunds issued from the
 * gateway dashboard, disputes) in the order ledger and history. Every method
 * locks the order row first, the same lock PaymentServiceImpl takes for a
 * capture or refund, so a webhook that arrives while the shop's own refund is
 * still committing waits for it and then sees its ledger entry. Each method is
 * idempotent: repeated or concurrent deliveries change nothing twice.
 */
@Service("gatewayEventRecorder")
public class GatewayEventRecorder {

	private static final Logger LOGGER = LoggerFactory.getLogger(GatewayEventRecorder.class);

	/** Outcome of {@link #recordRefunds}. */
	public static final class RefundResult {
		public int recorded;
		public int alreadyRecorded;
		public int rejected;
	}

	@PersistenceContext
	private EntityManager em;

	@Inject
	private TransactionService transactionService;

	/**
	 * Adds the gateway's refunds that the ledger does not hold yet, matched by the
	 * gateway refund id (TRANSACTIONID). A refund is only recorded against an order
	 * whose ledger has a captured payment and only up to the amount still
	 * refundable; anything else is logged for a person and left out. When nothing
	 * is left to refund the order moves to REFUNDED if its status allows it.
	 *
	 * @param refunds REFUND transactions built from the gateway's answer
	 */
	@Transactional(rollbackFor = Exception.class)
	public RefundResult recordRefunds(Long orderId, List<Transaction> refunds, String source) throws ServiceException {
		RefundResult result = new RefundResult();
		Order order = em.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE);
		if (order == null) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR, "Order " + orderId + " not found");
		}
		if (transactionService.getRefundableTransaction(order) == null) {
			LOGGER.warn("{}: order {} has no captured payment in the ledger; {} refund(s) not recorded", source,
					orderId, refunds.size());
			result.rejected = refunds.size();
			return result;
		}
		Set<String> known = new HashSet<>();
		for (Transaction existing : transactionService.listTransactions(order)) {
			if (existing.getTransactionType() == TransactionType.REFUND && existing.getTransactionDetails() != null) {
				known.add(existing.getTransactionDetails().get("TRANSACTIONID"));
			}
		}
		for (Transaction refund : refunds) {
			String refundId = refund.getTransactionDetails().get("TRANSACTIONID");
			if (StringUtils.isBlank(refundId) || known.contains(refundId)) {
				result.alreadyRecorded++;
				continue;
			}
			BigDecimal remaining = transactionService.getRefundableAmount(order);
			if (refund.getAmount() == null || refund.getAmount().signum() <= 0
					|| refund.getAmount().compareTo(remaining) > 0) {
				LOGGER.error("{}: refund {} of {} on order {} exceeds the refundable amount {}; not recorded,"
						+ " check the gateway against the ledger", source, refundId, refund.getAmount(), orderId,
						remaining);
				result.rejected++;
				continue;
			}
			refund.setOrder(order);
			refund.setTransactionType(TransactionType.REFUND);
			if (refund.getPaymentType() == null) {
				refund.setPaymentType(order.getPaymentType());
			}
			transactionService.create(refund);
			known.add(refundId);
			result.recorded++;
			BigDecimal left = remaining.subtract(refund.getAmount());
			String comment = source + ": refund " + refundId + " of " + refund.getAmount() + " recorded, remaining "
					+ "refundable " + left;
			if (left.signum() == 0 && OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.REFUNDED)) {
				order.setStatus(OrderStatus.REFUNDED);
			}
			addHistory(order, comment);
		}
		return result;
	}

	/**
	 * Adds a history entry for a gateway dispute event, once per dispute and event.
	 * When asked, an order still PAYMENT_PENDING moves to PAYMENT_REVIEW; the status
	 * table allows PAYMENT_REVIEW only from PAYMENT_PENDING, so a paid order keeps
	 * its status and the dispute is visible in its history.
	 *
	 * @return false when this event was already recorded
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean recordDispute(Long orderId, String disputeId, String event, String comment, boolean review)
			throws ServiceException {
		Order order = em.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE);
		if (order == null) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR, "Order " + orderId + " not found");
		}
		String marker = "dispute " + disputeId + " " + event;
		for (OrderStatusHistory h : order.getOrderHistory()) {
			if (h.getComments() != null && h.getComments().contains(marker)) {
				return false;
			}
		}
		if (review && OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.PAYMENT_REVIEW)) {
			order.setStatus(OrderStatus.PAYMENT_REVIEW);
		}
		addHistory(order, marker + ": " + comment);
		return true;
	}

	private static void addHistory(Order order, String comment) {
		OrderStatusHistory history = new OrderStatusHistory();
		history.setOrder(order);
		history.setStatus(order.getStatus());
		history.setDateAdded(new Date());
		history.setCustomerNotified(0);
		history.setComments(StringUtils.abbreviate(comment, 250));
		order.getOrderHistory().add(history);
	}
}
