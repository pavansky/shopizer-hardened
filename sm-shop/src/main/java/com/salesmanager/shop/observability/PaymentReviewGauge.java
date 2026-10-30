package com.salesmanager.shop.observability;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * shopizer_orders_payment_review{store}: how many orders are in PAYMENT_REVIEW right now.
 *
 * The reconciler's review counter only says that an order moved into review, so an alert
 * on its increase clears an hour later while the order still waits for a person. This
 * gauge is a state, so an alert on it stays on until every order has been resolved.
 * One grouped count query a minute; every known store gets a series, 0 when it has none.
 */
@Component
public class PaymentReviewGauge {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentReviewGauge.class);
	static final String NAME = "shopizer.orders.payment.review";

	@PersistenceContext
	private EntityManager em;

	private final MeterRegistry registry;
	private final KnownStores knownStores;
	private final ConcurrentMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

	public PaymentReviewGauge(MeterRegistry registry, KnownStores knownStores) {
		this.registry = registry;
		this.knownStores = knownStores;
	}

	@Scheduled(initialDelayString = "${shopizer.metrics.initial-delay-ms:10000}",
			fixedDelayString = "${shopizer.metrics.refresh-ms:60000}")
	public void refresh() {
		Map<String, Long> latest = new HashMap<>();
		try {
			List<Object[]> rows = em.createQuery(
					"select o.merchant.code, count(o) from Order o where o.status = :status group by o.merchant.code",
					Object[].class).setParameter("status", OrderStatus.PAYMENT_REVIEW).getResultList();
			for (Object[] row : rows) {
				latest.put((String) row[0], ((Number) row[1]).longValue());
			}
		} catch (RuntimeException e) {
			// keep the last values: a database outage must not look like "nothing in review"
			LOGGER.warn("Could not count orders in PAYMENT_REVIEW; keeping the last values", e);
			return;
		}
		for (String store : knownStores.codes()) {
			gauge(store);
		}
		latest.forEach((store, count) -> gauge(store).set(count));
		counts.forEach((store, value) -> {
			if (!latest.containsKey(store)) {
				value.set(0);
			}
		});
	}

	private AtomicLong gauge(String store) {
		return counts.computeIfAbsent(store, s -> registry.gauge(NAME, Tags.of("store", s), new AtomicLong()));
	}

	void setEntityManager(EntityManager em) {
		this.em = em;
	}
}
