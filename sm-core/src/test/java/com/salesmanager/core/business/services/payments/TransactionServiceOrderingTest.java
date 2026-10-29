package com.salesmanager.core.business.services.payments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;

import com.salesmanager.core.business.repositories.payments.TransactionRepository;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;

public class TransactionServiceOrderingTest {

	private TransactionRepository repository;
	private TransactionServiceImpl service;
	private final Order order = new Order();

	@Before
	public void setUp() {
		repository = mock(TransactionRepository.class);
		service = new TransactionServiceImpl(repository);
		order.setId(1L);
	}

	private static Transaction trx(long id, TransactionType type, long created, String amount) {
		Transaction t = new Transaction();
		t.setId(id);
		t.setTransactionType(type);
		t.setAmount(new BigDecimal(amount));
		t.getAuditSection().setDateCreated(new Date(created));
		return t;
	}

	@Test
	public void lastTransactionIsByDateNotByTypeName() throws Exception {
		// REFUND sorts last alphabetically but the AUTHORIZE is the newest
		Transaction refund = trx(1, TransactionType.REFUND, 1000, "10");
		Transaction authorize = trx(2, TransactionType.AUTHORIZE, 2000, "10");
		when(repository.findByOrder(1L)).thenReturn(Arrays.asList(authorize, refund));

		assertSame(authorize, service.lastTransaction(order, null));
	}

	@Test
	public void sameTimestampFallsBackToId() throws Exception {
		Transaction first = trx(10, TransactionType.CAPTURE, 1000, "10");
		Transaction second = trx(11, TransactionType.AUTHORIZE, 1000, "10");
		when(repository.findByOrder(1L)).thenReturn(Arrays.asList(second, first));

		assertSame(second, service.lastTransaction(order, null));
		assertSame(second, service.getCapturableTransaction(order));
	}

	@Test
	public void authorizeFollowedByCaptureIsNotCapturable() throws Exception {
		Transaction authorize = trx(1, TransactionType.AUTHORIZE, 1000, "10");
		Transaction capture = trx(2, TransactionType.CAPTURE, 2000, "10");
		when(repository.findByOrder(1L)).thenReturn(Arrays.asList(capture, authorize));

		assertNull(service.getCapturableTransaction(order));
		assertSame(capture, service.getRefundableTransaction(order));
	}

	@Test
	public void refundableAmountIsCapturedMinusRefunded() throws Exception {
		when(repository.findByOrder(1L)).thenReturn(Arrays.asList(
				trx(1, TransactionType.AUTHORIZECAPTURE, 1000, "100.00"),
				trx(2, TransactionType.REFUND, 2000, "30.00"),
				trx(3, TransactionType.REFUND, 3000, "20.00")));

		assertEquals(new BigDecimal("50.00"), service.getRefundableAmount(order));
	}
}
