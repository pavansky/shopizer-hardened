package com.salesmanager.core.business.services.order.idempotency;

import java.util.Date;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.model.order.idempotency.OrderIdempotencyKey;

/**
 * Not a TransactionalAspectAwareService on purpose: boundaries are declared
 * explicitly with REQUIRES_NEW so they commit independently of the caller.
 */
@Service("orderIdempotencyService")
public class OrderIdempotencyServiceImpl implements OrderIdempotencyService {

	@PersistenceContext
	private EntityManager em;

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public OrderIdempotencyKey findKey(Integer merchantId, String key) {
		List<OrderIdempotencyKey> rows = em
				.createQuery("select k from OrderIdempotencyKey k where k.merchantId = :m and k.idempotencyKey = :k",
						OrderIdempotencyKey.class)
				.setParameter("m", merchantId).setParameter("k", key).getResultList();
		return rows.isEmpty() ? null : rows.get(0);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public OrderIdempotencyKey claimKey(Integer merchantId, String key, String cartCode, String requestHash) {
		OrderIdempotencyKey row = new OrderIdempotencyKey();
		row.setMerchantId(merchantId);
		row.setIdempotencyKey(key);
		row.setCartCode(cartCode);
		row.setRequestHash(requestHash);
		row.setDateCreated(new Date());
		em.persist(row);
		em.flush();
		return row;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void completeKey(Long recordId, Long orderId) {
		OrderIdempotencyKey row = em.find(OrderIdempotencyKey.class, recordId);
		if (row != null) {
			row.setOrderId(orderId);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void releaseKey(Long recordId) {
		OrderIdempotencyKey row = em.find(OrderIdempotencyKey.class, recordId);
		if (row != null) {
			em.remove(row);
		}
	}
}
