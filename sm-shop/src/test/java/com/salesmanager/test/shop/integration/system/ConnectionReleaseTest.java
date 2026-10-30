package com.salesmanager.test.shop.integration.system;

import static org.junit.Assert.assertEquals;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.salesmanager.shop.application.ShopApplication;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Open-in-view keeps one EntityManager per web request, so lazy loading works in the
 * mappers. With Spring's default connection handling (DELAYED_ACQUISITION_AND_HOLD) that
 * EntityManager also kept its JDBC connection from the first query until the response
 * was written, including while checkout waited on the payment gateway between its short
 * transactions. DataConfiguration now sets DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION,
 * and this test checks the effect the way a request sees it: the EntityManager stays
 * open, the connection goes back to the pool.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class ConnectionReleaseTest {

	@Inject
	private EntityManagerFactory entityManagerFactory;

	@Inject
	private PlatformTransactionManager transactionManager;

	@Inject
	private HikariDataSource dataSource;

	@Test
	public void requestScopedEntityManagerDoesNotHoldItsConnection() {
		// what OpenEntityManagerInViewInterceptor does at the start of a request
		EntityManager em = entityManagerFactory.createEntityManager();
		TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
		try {
			int before = active();

			// a short transaction, like each checkout step
			new TransactionTemplate(transactionManager).execute(status ->
					em.createQuery("select count(l) from Language l").getSingleResult());
			assertEquals("connection kept after the transaction", before, active());

			// a read outside any transaction, like a lazy load in a mapper
			em.createQuery("select count(c) from Country c").getSingleResult();
			assertEquals("connection kept after a non-transactional read", before, active());

			// lazy loading still works: the EntityManager is open
			em.createQuery("select s from MerchantStore s", com.salesmanager.core.model.merchant.MerchantStore.class)
					.getResultList().forEach(s -> s.getLanguages().size());
		} finally {
			TransactionSynchronizationManager.unbindResource(entityManagerFactory);
			em.close();
		}
	}

	private int active() {
		return dataSource.getHikariPoolMXBean().getActiveConnections();
	}
}
