package com.salesmanager.core.business.services.order.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Date;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;

class OrderIdempotencyServiceCleanupTest {

	private OrderIdempotencyServiceImpl service;
	private EntityManager em;
	private Query query;

	@BeforeEach
	void setUp() throws Exception {
		service = new OrderIdempotencyServiceImpl();
		em = mock(EntityManager.class);
		query = mock(Query.class, RETURNS_SELF);
		when(em.createQuery(anyString())).thenReturn(query);
		Field f = OrderIdempotencyServiceImpl.class.getDeclaredField("em");
		f.setAccessible(true);
		f.set(service, em);
	}

	@Test
	void deletesOnlyOldKeysWhoseOrderIsNotPending() {
		Date cutoff = new Date(1000L);
		when(query.executeUpdate()).thenReturn(4);

		assertEquals(4, service.deleteExpiredKeys(cutoff));

		ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
		verify(em).createQuery(jpql.capture());
		String q = jpql.getValue();
		assertTrue(q.startsWith("delete from OrderIdempotencyKey k where k.dateCreated < :c"), q);
		assertTrue(q.contains("not exists"), q);
		assertTrue(q.contains("o.status = :pending"), q);
		verify(query).setParameter("c", cutoff);
		verify(query).setParameter("pending", OrderStatus.PAYMENT_PENDING);
	}

	@Test
	void releasesKeysBoundToOrder() {
		when(query.executeUpdate()).thenReturn(1);

		assertEquals(1, service.releaseKeysForOrder(8L));

		verify(em).createQuery("delete from OrderIdempotencyKey k where k.orderId = :o");
		verify(query).setParameter("o", 8L);
	}
}
