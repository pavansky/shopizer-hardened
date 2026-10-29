package com.salesmanager.core.business.services.order.reconciliation;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.salesmanager.core.business.services.order.OrderCheckoutSteps;
import com.salesmanager.core.business.services.order.idempotency.OrderIdempotencyService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;

/**
 * Settles orders left in PAYMENT_PENDING after a crash, a timeout or a failed
 * finalise step, gives back late charges on orders already failed, and
 * expires old idempotency keys.
 *
 * The gateway is the source of truth. An order whose payment attempt has no
 * clear outcome (INITIATED or UNKNOWN) is only failed when the gateway says
 * the payment failed, or has no trace of it after a safe delay. Without a
 * gateway answer the order waits, and after a day it moves to PAYMENT_REVIEW
 * for a person to decide.
 *
 * Every order is handled through the OrderCheckoutSteps proxy, so each one runs
 * in its own REQUIRES_NEW transaction and a failure only affects that order.
 * The steps claim the order with a compare-and-set on its status, so several
 * instances may run this job at the same time without settling an order twice.
 */
@Component
public class PendingOrderReconciler {

	private static final Logger LOGGER = LoggerFactory.getLogger(PendingOrderReconciler.class);

	static final String PAID_COMMENT = "reconciled: payment found";
	static final String UNPAID_REASON = "reconciled: no payment recorded";
	static final String CONFIRMED_COMMENT = "reconciled: payment confirmed by gateway";
	static final String GATEWAY_FAILED_REASON = "reconciled: gateway reports payment failed";
	static final String NOT_FOUND_REASON = "reconciled: gateway has no payment";
	static final String REVIEW_COMMENT = "payment not confirmed by gateway; needs review";
	static final String COMPENSATED_COMMENT = "compensated: late charge returned";

	private final OrderCheckoutSteps steps;
	private final PendingOrderQueries queries;
	private final OrderIdempotencyService idempotencyService;
	private final ObjectProvider<ReconciliationMetrics> metrics;
	private final PaymentAttemptService attempts;
	private final PaymentGateways gateways;

	@Value("${config.reconciliation.pendingOrders.enabled:true}")
	private boolean enabled = true;

	@Value("${config.reconciliation.pendingOrders.graceMinutes:15}")
	private long graceMinutes = 15;

	@Value("${config.reconciliation.pendingOrders.batchSize:100}")
	private int batchSize = 100;

	/** Upper bound of batches per run so a single run cannot hold the scheduler thread indefinitely. */
	@Value("${config.reconciliation.pendingOrders.maxBatches:50}")
	private int maxBatches = 50;

	/** An attempt the gateway does not know is only treated as never charged once it is this old. */
	@Value("${config.reconciliation.notFoundSafeMinutes:30}")
	private long notFoundSafeMinutes = 30;

	/** An attempt still without a gateway answer after this long moves its order to PAYMENT_REVIEW. */
	@Value("${config.reconciliation.reviewAfterMinutes:1440}")
	private long reviewAfterMinutes = 1440;

	@Value("${config.idempotency.cleanup.enabled:true}")
	private boolean cleanupEnabled = true;

	@Value("${config.idempotency.retentionHours:48}")
	private long retentionHours = 48;

	public PendingOrderReconciler(OrderCheckoutSteps steps, PendingOrderQueries queries,
			OrderIdempotencyService idempotencyService, PaymentAttemptService attempts, PaymentGateways gateways,
			ObjectProvider<ReconciliationMetrics> metrics) {
		this.steps = steps;
		this.queries = queries;
		this.idempotencyService = idempotencyService;
		this.attempts = attempts;
		this.gateways = gateways;
		this.metrics = metrics;
	}

	/** Outcome counts of one run, returned for logging and tests. */
	public static final class Result {
		public int finalised;
		public int failed;
		public int skipped;
		public int errors;
		/** Finalised because the gateway confirmed a payment the shop never recorded. */
		public int confirmed;
		/** Moved to PAYMENT_REVIEW. */
		public int review;
		/** Left as they were, waiting for a gateway answer. */
		public int waiting;
		public int lookupErrors;
	}

	/** Outcome counts of one orphan compensation run. */
	public static final class OrphanResult {
		public int compensated;
		public int closed;
		public int waiting;
		public int compensationNeeded;
		public int errors;
	}

	@Scheduled(fixedDelayString = "${config.reconciliation.pendingOrders.delayMs:300000}",
			initialDelayString = "${config.reconciliation.pendingOrders.initialDelayMs:60000}")
	public void scheduledReconcile() {
		reconcilePendingOrders();
		compensateOrphanAttempts();
	}

	public Result reconcilePendingOrders() {
		Result result = new Result();
		if (!enabled) {
			return result;
		}
		Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(graceMinutes));
		Long afterId = 0L;
		for (int batch = 0; batch < maxBatches; batch++) {
			List<Long> ids;
			try {
				ids = queries.findStalePendingOrderIds(cutoff, afterId, batchSize);
			} catch (RuntimeException e) {
				LOGGER.error("Could not load PAYMENT_PENDING orders for reconciliation", e);
				result.errors++;
				break;
			}
			for (Long id : ids) {
				reconcileOne(id, result);
				afterId = id;
			}
			if (ids.size() < batchSize) {
				break;
			}
		}
		if (result.finalised + result.failed + result.skipped + result.errors + result.review + result.waiting > 0) {
			LOGGER.info("Pending order reconciliation: {} finalised ({} confirmed by gateway), {} marked PAYMENT_FAILED,"
					+ " {} moved to PAYMENT_REVIEW, {} waiting for the gateway, {} already settled, {} errors",
					result.finalised, result.confirmed, result.failed, result.review, result.waiting, result.skipped,
					result.errors);
		}
		return result;
	}

	/**
	 * Settles one order now, for example when a gateway webhook reports a change.
	 * Uses the same rules as the scheduled run: the gateway is asked again, so the
	 * webhook body itself is never trusted, and the compare-and-set claim makes
	 * duplicate or concurrent deliveries harmless.
	 */
	public Result reconcileOrder(Long orderId) {
		Result result = new Result();
		reconcileOne(orderId, result);
		return result;
	}

	private void reconcileOne(Long orderId, Result result) {
		try {
			if (queries.hasSuccessfulPayment(orderId) || attempts.hasSucceeded(orderId)) {
				count(steps.reconcilePaidOrder(orderId, PAID_COMMENT), result, () -> {
					result.finalised++;
					metrics.ifAvailable(ReconciliationMetrics::finalised);
					LOGGER.info("Order {} reconciled: payment found, order finalised", orderId);
				});
				return;
			}
			PaymentAttempt attempt = attempts.latestForOrder(orderId);
			if (attempt == null || attempt.getStatus() == PaymentAttemptStatus.FAILED) {
				// No attempt means the gateway was never called (or the order predates
				// attempts); a FAILED attempt is a definitive decline.
				failOrder(orderId, UNPAID_REASON, null, result);
				return;
			}
			resolveWithGateway(orderId, attempt, result);
		} catch (Exception e) {
			result.errors++;
			metrics.ifAvailable(ReconciliationMetrics::error);
			LOGGER.error("Could not reconcile PAYMENT_PENDING order " + orderId, e);
		}
	}

	/** The attempt is INITIATED or UNKNOWN: only the gateway can say whether money was taken. */
	private void resolveWithGateway(Long orderId, PaymentAttempt attempt, Result result) throws Exception {
		GatewayPaymentStatus status;
		try {
			status = gateways.lookup(attempt);
		} catch (Exception e) {
			result.lookupErrors++;
			metrics.ifAvailable(ReconciliationMetrics::lookupError);
			LOGGER.warn("Could not ask the gateway about attempt " + attempt.getAttemptRef() + " of order " + orderId
					+ "; order left PAYMENT_PENDING", e);
			reviewIfOld(orderId, attempt, "lookup failed: " + e.getMessage(), result);
			return;
		}
		switch (status.getOutcome()) {
		case SUCCEEDED:
			count(steps.reconcileConfirmedPayment(orderId, attempt.getId(), paymentTransaction(attempt, status),
					CONFIRMED_COMMENT), result, () -> {
						result.finalised++;
						result.confirmed++;
						metrics.ifAvailable(ReconciliationMetrics::confirmedByGateway);
						LOGGER.warn("Order {} reconciled: gateway confirmed payment {} that was never recorded",
								orderId, status.getGatewayTransactionId());
					});
			break;
		case FAILED:
			failOrder(orderId, GATEWAY_FAILED_REASON + " (" + status.getDetail() + ")", attempt.getId(), result);
			break;
		case NOT_FOUND:
			if (olderThan(attempt, notFoundSafeMinutes)) {
				failOrder(orderId, NOT_FOUND_REASON, attempt.getId(), result);
			} else {
				result.waiting++;
			}
			break;
		default:
			reviewIfOld(orderId, attempt, status.toString(), result);
		}
	}

	private void reviewIfOld(Long orderId, PaymentAttempt attempt, String why, Result result) {
		if (!olderThan(attempt, reviewAfterMinutes)) {
			result.waiting++;
			return;
		}
		count(steps.moveToPaymentReview(orderId, REVIEW_COMMENT + " (" + why + ")"), result, () -> {
			result.review++;
			metrics.ifAvailable(ReconciliationMetrics::review);
			LOGGER.error("Order {} moved to PAYMENT_REVIEW: payment attempt {} has no gateway answer after {}"
					+ " minutes ({}). Check the gateway and settle the order by hand.", orderId,
					attempt.getAttemptRef(), reviewAfterMinutes, why);
		});
	}

	private void failOrder(Long orderId, String reason, Long attemptId, Result result) {
		boolean claimed = attemptId == null ? steps.reconcileUnpaidOrder(orderId, reason)
				: steps.reconcileUnpaidOrder(orderId, reason, attemptId);
		count(claimed, result, () -> {
			result.failed++;
			metrics.ifAvailable(ReconciliationMetrics::failed);
			LOGGER.info("Order {} reconciled: {}, marked PAYMENT_FAILED", orderId, reason);
			releaseKeys(orderId);
		});
	}

	private void count(boolean claimed, Result result, Runnable onClaimed) {
		if (claimed) {
			onClaimed.run();
		} else {
			result.skipped++;
			metrics.ifAvailable(ReconciliationMetrics::skipped);
		}
	}

	private static boolean olderThan(PaymentAttempt attempt, long minutes) {
		Date created = attempt.getDateCreated();
		return created != null && created.getTime() < System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(minutes);
	}

	/** The payment transaction the checkout never stored, rebuilt from the gateway's answer. */
	static Transaction paymentTransaction(PaymentAttempt attempt, GatewayPaymentStatus status) {
		Transaction tx = new Transaction();
		tx.setAmount(status.getAmount() != null ? status.getAmount() : attempt.getAmount());
		tx.setTransactionDate(new Date());
		tx.setTransactionType(status.isCaptured() ? TransactionType.AUTHORIZECAPTURE : TransactionType.AUTHORIZE);
		String id = status.getGatewayTransactionId() != null ? status.getGatewayTransactionId()
				: attempt.getGatewayRef();
		tx.getTransactionDetails().put("TRANSACTIONID", id);
		tx.getTransactionDetails().put("TRNORDERNUMBER", id);
		tx.getTransactionDetails().put("TRNAPPROVED", status.getDetail());
		tx.getTransactionDetails().put("ATTEMPTREF", attempt.getAttemptRef());
		tx.getTransactionDetails().put("MESSAGETEXT", CONFIRMED_COMMENT);
		return tx;
	}

	/**
	 * Second pass: attempts still INITIATED or UNKNOWN whose order the shop has
	 * already failed or cancelled. If the gateway took the money anyway, it is
	 * given back through the module. If the module cannot do that, nothing is
	 * changed and the case is logged and counted for a person to handle.
	 */
	public OrphanResult compensateOrphanAttempts() {
		OrphanResult result = new OrphanResult();
		if (!enabled) {
			return result;
		}
		Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(graceMinutes));
		Long afterId = 0L;
		for (int batch = 0; batch < maxBatches; batch++) {
			List<PaymentAttempt> orphans;
			try {
				orphans = attempts.findOrphans(cutoff, afterId, batchSize);
			} catch (RuntimeException e) {
				LOGGER.error("Could not load orphan payment attempts", e);
				result.errors++;
				break;
			}
			for (PaymentAttempt attempt : orphans) {
				compensateOne(attempt, result);
				afterId = attempt.getId();
			}
			if (orphans.size() < batchSize) {
				break;
			}
		}
		if (result.compensated + result.closed + result.compensationNeeded + result.errors > 0) {
			LOGGER.info("Orphan payment attempts: {} compensated, {} closed, {} waiting, {} need manual compensation,"
					+ " {} errors", result.compensated, result.closed, result.waiting, result.compensationNeeded,
					result.errors);
		}
		return result;
	}

	private void compensateOne(PaymentAttempt attempt, OrphanResult result) {
		GatewayPaymentStatus status;
		try {
			status = gateways.lookup(attempt);
		} catch (Exception e) {
			result.errors++;
			metrics.ifAvailable(ReconciliationMetrics::lookupError);
			LOGGER.warn("Could not ask the gateway about orphan attempt " + attempt.getAttemptRef(), e);
			return;
		}
		try {
			switch (status.getOutcome()) {
			case SUCCEEDED:
				Transaction refund;
				try {
					refund = gateways.compensate(attempt, status);
				} catch (Exception e) {
					needsCompensation(attempt, status, e.getMessage(), result);
					return;
				}
				if (refund == null) {
					needsCompensation(attempt, status, "module cannot compensate", result);
					return;
				}
				steps.recordCompensation(attempt.getOrderId(), attempt.getId(), refund, COMPENSATED_COMMENT);
				result.compensated++;
				metrics.ifAvailable(ReconciliationMetrics::compensated);
				LOGGER.warn("Order {}: late charge {} of attempt {} returned", attempt.getOrderId(),
						status.getGatewayTransactionId(), attempt.getAttemptRef());
				break;
			case FAILED:
				attempts.update(attempt.getId(), PaymentAttemptStatus.FAILED, status.getGatewayTransactionId(),
						GATEWAY_FAILED_REASON + " (" + status.getDetail() + ")");
				result.closed++;
				break;
			case NOT_FOUND:
				if (olderThan(attempt, notFoundSafeMinutes)) {
					attempts.update(attempt.getId(), PaymentAttemptStatus.FAILED, null, NOT_FOUND_REASON);
					result.closed++;
				} else {
					result.waiting++;
				}
				break;
			default:
				result.waiting++;
			}
		} catch (Exception e) {
			result.errors++;
			metrics.ifAvailable(ReconciliationMetrics::error);
			LOGGER.error("Could not settle orphan payment attempt " + attempt.getAttemptRef(), e);
		}
	}

	private void needsCompensation(PaymentAttempt attempt, GatewayPaymentStatus status, String why,
			OrphanResult result) {
		result.compensationNeeded++;
		metrics.ifAvailable(ReconciliationMetrics::compensationNeeded);
		LOGGER.error("Order {} is {} but the gateway took payment {} (attempt {}) and it could not be returned"
				+ " automatically: {}. Refund it by hand.", attempt.getOrderId(), "failed or cancelled",
				status.getGatewayTransactionId(), attempt.getAttemptRef(), why);
	}

	private void releaseKeys(Long orderId) {
		try {
			idempotencyService.releaseKeysForOrder(orderId);
		} catch (RuntimeException e) {
			// The order is already settled; the key expires through the cleanup job.
			LOGGER.error("Order " + orderId + " marked PAYMENT_FAILED but its idempotency key was not released", e);
		}
	}

	@Scheduled(fixedDelayString = "${config.idempotency.cleanup.delayMs:3600000}",
			initialDelayString = "${config.idempotency.cleanup.initialDelayMs:120000}")
	public void scheduledKeyCleanup() {
		cleanupIdempotencyKeys();
	}

	public int cleanupIdempotencyKeys() {
		if (!cleanupEnabled) {
			return 0;
		}
		Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(retentionHours));
		try {
			int deleted = idempotencyService.deleteExpiredKeys(cutoff);
			if (deleted > 0) {
				LOGGER.info("Deleted {} idempotency keys older than {} hours", deleted, retentionHours);
				metrics.ifAvailable(m -> m.keysDeleted(deleted));
			}
			return deleted;
		} catch (RuntimeException e) {
			LOGGER.error("Idempotency key cleanup failed", e);
			return 0;
		}
	}
}
