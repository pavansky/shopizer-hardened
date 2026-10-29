package com.salesmanager.core.business.services.payments.attempt;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;

/**
 * Not a TransactionalAspectAwareService on purpose: boundaries are declared
 * explicitly so each write commits independently of the caller.
 */
@Service("paymentAttemptService")
public class PaymentAttemptServiceImpl implements PaymentAttemptService {

	private static final List<PaymentAttemptStatus> OPEN = Arrays.asList(PaymentAttemptStatus.INITIATED,
			PaymentAttemptStatus.UNKNOWN);
	private static final List<OrderStatus> CLOSED_UNPAID = Arrays.asList(OrderStatus.PAYMENT_FAILED,
			OrderStatus.CANCELED);

	@PersistenceContext
	private EntityManager em;

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PaymentAttempt begin(PaymentAttempt attempt) {
		Date now = new Date();
		if (StringUtils.isBlank(attempt.getAttemptRef())) {
			attempt.setAttemptRef(UUID.randomUUID().toString());
		}
		attempt.setStatus(PaymentAttemptStatus.INITIATED);
		attempt.setDateCreated(now);
		attempt.setDateUpdated(now);
		em.persist(attempt);
		em.flush();
		return attempt;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean update(Long attemptId, PaymentAttemptStatus status, String gatewayRef, String reason) {
		return PaymentAttempts.apply(em, attemptId, status, gatewayRef, reason);
	}

	@Override
	@Transactional(readOnly = true)
	public PaymentAttempt latestForOrder(Long orderId) {
		List<PaymentAttempt> rows = em
				.createQuery("select a from PaymentAttempt a where a.orderId = :o order by a.id desc",
						PaymentAttempt.class)
				.setParameter("o", orderId).setMaxResults(1).getResultList();
		return rows.isEmpty() ? null : rows.get(0);
	}

	@Override
	@Transactional(readOnly = true)
	public PaymentAttempt findByGatewayRef(String gatewayRef) {
		List<PaymentAttempt> rows = em
				.createQuery("select a from PaymentAttempt a where a.gatewayRef = :g order by a.id desc",
						PaymentAttempt.class)
				.setParameter("g", gatewayRef).setMaxResults(1).getResultList();
		return rows.isEmpty() ? null : rows.get(0);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasSucceeded(Long orderId) {
		Long count = em.createQuery("select count(a) from PaymentAttempt a where a.orderId = :o and a.status = :s",
				Long.class).setParameter("o", orderId).setParameter("s", PaymentAttemptStatus.SUCCEEDED)
				.getSingleResult();
		return count != null && count > 0;
	}

	@Override
	@Transactional(readOnly = true)
	public List<PaymentAttempt> findOrphans(Date createdBefore, Long afterId, int limit) {
		return em.createQuery("select a from PaymentAttempt a where a.status in :open and a.dateCreated < :cutoff"
				+ " and a.id > :after and exists (select o.id from Order o where o.id = a.orderId"
				+ " and o.status in :closed) order by a.id", PaymentAttempt.class)
				.setParameter("open", OPEN)
				.setParameter("cutoff", createdBefore)
				.setParameter("after", afterId == null ? 0L : afterId)
				.setParameter("closed", CLOSED_UNPAID)
				.setMaxResults(limit)
				.getResultList();
	}
}
