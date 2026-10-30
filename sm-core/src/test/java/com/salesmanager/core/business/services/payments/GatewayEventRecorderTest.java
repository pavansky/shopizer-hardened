package com.salesmanager.core.business.services.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;

class GatewayEventRecorderTest {

	private final GatewayEventRecorder recorder = new GatewayEventRecorder();
	private EntityManager em;
	private TransactionService transactions;
	private final Order order = new Order();
	private final List<Transaction> ledger = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		em = mock(EntityManager.class);
		transactions = mock(TransactionService.class);
		ReflectionTestUtils.setField(recorder, "em", em);
		ReflectionTestUtils.setField(recorder, "transactionService", transactions);
		order.setId(42L);
		order.setStatus(OrderStatus.PROCESSED);
		order.setOrderHistory(new java.util.LinkedHashSet<>());
		when(em.find(Order.class, 42L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(order);

		Transaction captured = tx(TransactionType.AUTHORIZECAPTURE, "pi_1", "100.00");
		ledger.add(captured);
		when(transactions.getRefundableTransaction(order)).thenReturn(captured);
		when(transactions.listTransactions(order)).thenAnswer(inv -> new ArrayList<>(ledger));
		// captured minus what the ledger holds as refunded
		when(transactions.getRefundableAmount(order)).thenAnswer(inv -> {
			BigDecimal left = new BigDecimal("100.00");
			for (Transaction t : ledger) {
				if (t.getTransactionType() == TransactionType.REFUND) {
					left = left.subtract(t.getAmount());
				}
			}
			return left;
		});
		org.mockito.Mockito.doAnswer(inv -> ledger.add(inv.getArgument(0))).when(transactions).create(any());
	}

	private static Transaction tx(TransactionType type, String id, String amount) {
		Transaction t = new Transaction();
		t.setTransactionType(type);
		t.setAmount(new BigDecimal(amount));
		t.getTransactionDetails().put("TRANSACTIONID", id);
		return t;
	}

	@Test
	void dashboardRefundIsRecordedOnceAndKeepsAPartlyRefundedOrderStatus() throws Exception {
		GatewayEventRecorder.RefundResult first = recorder.recordRefunds(42L,
				Collections.singletonList(tx(TransactionType.REFUND, "re_1", "30.00")), "Stripe");
		GatewayEventRecorder.RefundResult again = recorder.recordRefunds(42L,
				Collections.singletonList(tx(TransactionType.REFUND, "re_1", "30.00")), "Stripe");

		assertEquals(1, first.recorded);
		assertEquals(0, again.recorded);
		assertEquals(1, again.alreadyRecorded);
		ArgumentCaptor<Transaction> created = ArgumentCaptor.forClass(Transaction.class);
		verify(transactions).create(created.capture());
		assertEquals(order, created.getValue().getOrder());
		assertEquals(OrderStatus.PROCESSED, order.getStatus());
		assertTrue(order.getOrderHistory().iterator().next().getComments().contains("refund re_1 of 30.00"));
	}

	@Test
	void refundTheShopAlreadyRecordedIsSkipped() throws Exception {
		ledger.add(tx(TransactionType.REFUND, "re_shop", "100.00"));

		GatewayEventRecorder.RefundResult result = recorder.recordRefunds(42L,
				Collections.singletonList(tx(TransactionType.REFUND, "re_shop", "100.00")), "Stripe");

		assertEquals(0, result.recorded);
		verify(transactions, never()).create(any());
	}

	@Test
	void fullRefundMovesTheOrderToRefunded() throws Exception {
		recorder.recordRefunds(42L, Arrays.asList(tx(TransactionType.REFUND, "re_1", "40.00"),
				tx(TransactionType.REFUND, "re_2", "60.00")), "Stripe");

		assertEquals(OrderStatus.REFUNDED, order.getStatus());
		assertEquals(2, order.getOrderHistory().size());
	}

	@Test
	void refundAboveWhatIsRefundableIsNotRecorded() throws Exception {
		GatewayEventRecorder.RefundResult result = recorder.recordRefunds(42L,
				Collections.singletonList(tx(TransactionType.REFUND, "re_big", "150.00")), "Stripe");

		assertEquals(1, result.rejected);
		verify(transactions, never()).create(any());
	}

	@Test
	void orderWithoutACapturedPaymentGetsNoRefund() throws Exception {
		when(transactions.getRefundableTransaction(order)).thenReturn(null);

		GatewayEventRecorder.RefundResult result = recorder.recordRefunds(42L,
				Collections.singletonList(tx(TransactionType.REFUND, "re_1", "10.00")), "Stripe");

		assertEquals(1, result.rejected);
		verify(transactions, never()).create(any());
	}

	@Test
	void disputeIsWrittenOnceAndAPaidOrderKeepsItsStatus() throws Exception {
		assertTrue(recorder.recordDispute(42L, "dp_1", "opened", "reason fraudulent", true));
		assertFalse(recorder.recordDispute(42L, "dp_1", "opened", "reason fraudulent", true));

		assertEquals(1, order.getOrderHistory().size());
		OrderStatusHistory h = order.getOrderHistory().iterator().next();
		assertTrue(h.getComments().startsWith("dispute dp_1 opened"));
		// PROCESSED -> PAYMENT_REVIEW is not an allowed transition
		assertEquals(OrderStatus.PROCESSED, order.getStatus());
		assertTrue(recorder.recordDispute(42L, "dp_1", "closed", "won", false));
		assertEquals(2, order.getOrderHistory().size());
	}

	@Test
	void disputeOnAPendingOrderMovesItToReview() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_PENDING);

		recorder.recordDispute(42L, "dp_2", "opened", "reason fraudulent", true);

		assertEquals(OrderStatus.PAYMENT_REVIEW, order.getStatus());
	}
}
