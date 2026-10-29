package com.salesmanager.core.business.services.order.reconciliation;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.TransactionType;

/**
 * Read-only queries used by the pending order reconciler. Kept separate from
 * the transaction repository so the payment code is not touched.
 */
@Repository
public class PendingOrderQueries {

	static final List<TransactionType> SUCCESSFUL_PAYMENT = Arrays.asList(TransactionType.AUTHORIZE,
			TransactionType.AUTHORIZECAPTURE, TransactionType.CAPTURE);

	@PersistenceContext
	private EntityManager em;

	/** Ids of PAYMENT_PENDING orders purchased before the cutoff, ascending, after the given id. */
	@Transactional(readOnly = true)
	public List<Long> findStalePendingOrderIds(Date purchasedBefore, Long afterId, int limit) {
		return em.createQuery("select o.id from Order o where o.status = :pending"
				+ " and o.datePurchased < :cutoff and o.id > :after order by o.id", Long.class)
				.setParameter("pending", OrderStatus.PAYMENT_PENDING)
				.setParameter("cutoff", purchasedBefore)
				.setParameter("after", afterId == null ? 0L : afterId)
				.setMaxResults(limit)
				.getResultList();
	}

	/** True when a successful payment transaction (authorize, capture or both) is stored for the order. */
	@Transactional(readOnly = true)
	public boolean hasSuccessfulPayment(Long orderId) {
		Long count = em.createQuery("select count(t) from Transaction t where t.order.id = :o"
				+ " and t.transactionType in :types", Long.class)
				.setParameter("o", orderId)
				.setParameter("types", SUCCESSFUL_PAYMENT)
				.getSingleResult();
		return count != null && count > 0;
	}
}
