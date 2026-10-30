package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.TypedQuery;

import org.junit.Before;
import org.junit.Test;

import com.salesmanager.core.model.order.orderstatus.OrderStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class PaymentReviewGaugeTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final KnownStores knownStores = new KnownStores();
	private final EntityManager em = mock(EntityManager.class);
	@SuppressWarnings("unchecked")
	private final TypedQuery<Object[]> query = mock(TypedQuery.class);
	private PaymentReviewGauge gauge;

	@Before
	public void setUp() {
		knownStores.setCodes(new HashSet<>(Arrays.asList("DEFAULT", "B2B")));
		when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
		when(query.setParameter(eq("status"), any())).thenReturn(query);
		gauge = new PaymentReviewGauge(registry, knownStores);
		gauge.setEntityManager(em);
	}

	@Test
	public void countsPerStoreAndZeroForStoresWithNone() {
		when(query.getResultList()).thenReturn(rows(new Object[] { "DEFAULT", 2L }));
		gauge.refresh();
		assertEquals(2.0, value("DEFAULT"), 0);
		assertEquals(0.0, value("B2B"), 0);
	}

	@Test
	public void staysUpWhileOrdersRemainAndDropsToZeroWhenResolved() {
		when(query.getResultList()).thenReturn(rows(new Object[] { "DEFAULT", 1L }));
		gauge.refresh();
		gauge.refresh(); // an hour later the order is still in review: still 1, unlike the transition counter
		assertEquals(1.0, value("DEFAULT"), 0);

		when(query.getResultList()).thenReturn(Collections.emptyList());
		gauge.refresh();
		assertEquals(0.0, value("DEFAULT"), 0);
	}

	@Test
	public void keepsTheLastValueWhenTheDatabaseCannotBeQueried() {
		when(query.getResultList()).thenReturn(rows(new Object[] { "DEFAULT", 3L }));
		gauge.refresh();
		when(query.getResultList()).thenThrow(new PersistenceException("db down"));
		gauge.refresh();
		assertEquals(3.0, value("DEFAULT"), 0);
	}

	@Test
	public void queriesTheReviewStatus() {
		when(query.getResultList()).thenReturn(Collections.emptyList());
		gauge.refresh();
		org.mockito.Mockito.verify(query).setParameter("status", OrderStatus.PAYMENT_REVIEW);
	}

	private double value(String store) {
		return registry.get(PaymentReviewGauge.NAME).tag("store", store).gauge().value();
	}

	private static List<Object[]> rows(Object[]... rows) {
		return Arrays.asList(rows);
	}
}
