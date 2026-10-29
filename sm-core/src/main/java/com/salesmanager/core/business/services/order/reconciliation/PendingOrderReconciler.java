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

/**
 * Settles orders left in PAYMENT_PENDING after a crash or a failed finalise
 * step, and expires old idempotency keys.
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

	private final OrderCheckoutSteps steps;
	private final PendingOrderQueries queries;
	private final OrderIdempotencyService idempotencyService;
	private final ObjectProvider<ReconciliationMetrics> metrics;

	@Value("${config.reconciliation.pendingOrders.enabled:true}")
	private boolean enabled = true;

	@Value("${config.reconciliation.pendingOrders.graceMinutes:15}")
	private long graceMinutes = 15;

	@Value("${config.reconciliation.pendingOrders.batchSize:100}")
	private int batchSize = 100;

	/** Upper bound of batches per run so a single run cannot hold the scheduler thread indefinitely. */
	@Value("${config.reconciliation.pendingOrders.maxBatches:50}")
	private int maxBatches = 50;

	@Value("${config.idempotency.cleanup.enabled:true}")
	private boolean cleanupEnabled = true;

	@Value("${config.idempotency.retentionHours:48}")
	private long retentionHours = 48;

	public PendingOrderReconciler(OrderCheckoutSteps steps, PendingOrderQueries queries,
			OrderIdempotencyService idempotencyService, ObjectProvider<ReconciliationMetrics> metrics) {
		this.steps = steps;
		this.queries = queries;
		this.idempotencyService = idempotencyService;
		this.metrics = metrics;
	}

	/** Outcome counts of one run, returned for logging and tests. */
	public static final class Result {
		public int finalised;
		public int failed;
		public int skipped;
		public int errors;
	}

	@Scheduled(fixedDelayString = "${config.reconciliation.pendingOrders.delayMs:300000}",
			initialDelayString = "${config.reconciliation.pendingOrders.initialDelayMs:60000}")
	public void scheduledReconcile() {
		reconcilePendingOrders();
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
		if (result.finalised + result.failed + result.skipped + result.errors > 0) {
			LOGGER.info("Pending order reconciliation: {} finalised, {} marked PAYMENT_FAILED, {} already settled,"
					+ " {} errors", result.finalised, result.failed, result.skipped, result.errors);
		}
		return result;
	}

	private void reconcileOne(Long orderId, Result result) {
		try {
			boolean claimed;
			if (queries.hasSuccessfulPayment(orderId)) {
				claimed = steps.reconcilePaidOrder(orderId, PAID_COMMENT);
				if (claimed) {
					result.finalised++;
					metrics.ifAvailable(ReconciliationMetrics::finalised);
					LOGGER.info("Order {} reconciled: payment found, order finalised", orderId);
				}
			} else {
				claimed = steps.reconcileUnpaidOrder(orderId, UNPAID_REASON);
				if (claimed) {
					result.failed++;
					metrics.ifAvailable(ReconciliationMetrics::failed);
					LOGGER.info("Order {} reconciled: no payment recorded, marked PAYMENT_FAILED", orderId);
					releaseKeys(orderId);
				}
			}
			if (!claimed) {
				result.skipped++;
				metrics.ifAvailable(ReconciliationMetrics::skipped);
			}
		} catch (Exception e) {
			result.errors++;
			metrics.ifAvailable(ReconciliationMetrics::error);
			LOGGER.error("Could not reconcile PAYMENT_PENDING order " + orderId, e);
		}
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
