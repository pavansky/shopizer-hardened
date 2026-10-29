package com.salesmanager.shop.store.controller.order.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.store.api.exception.ConflictException;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.exception.RestApiException;

class OrderFacadePaymentTest {

	private OrderFacadeImpl facade;
	private OrderService orderService;
	private TransactionService transactionService;
	private PaymentService paymentService;
	private final MerchantStore store = new MerchantStore();
	private final Language language = new Language();
	private final Order order = new Order();

	@BeforeEach
	void setUp() throws Exception {
		store.setId(1);
		store.setCode("DEFAULT");
		order.setId(9L);
		facade = new OrderFacadeImpl();
		orderService = mock(OrderService.class);
		transactionService = mock(TransactionService.class);
		paymentService = mock(PaymentService.class);
		inject("orderService", orderService);
		inject("transactionService", transactionService);
		inject("paymentService", paymentService);
		inject("customerService", mock(CustomerService.class));
		when(orderService.getOrder(9L, store)).thenReturn(order);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = OrderFacadeImpl.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(facade, value);
	}

	private void refundable(String remaining) throws Exception {
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal(remaining));
	}

	@Test
	void orderOutsideStoreIsNotFound() {
		assertThrows(ResourceNotFoundException.class, () -> facade.captureOrder(store, 77L, language));
		assertThrows(ResourceNotFoundException.class, () -> facade.refundOrder(store, 77L, null, language));
	}

	@Test
	void nothingCapturableIsConflict() throws Exception {
		when(transactionService.getCapturableTransaction(order)).thenReturn(null);
		assertThrows(ConflictException.class, () -> facade.captureOrder(store, 9L, language));
		verify(paymentService, never()).processCapturePayment(any(), any(), any());
	}

	@Test
	void nothingCapturedIsConflict() throws Exception {
		when(transactionService.getRefundableTransaction(order)).thenReturn(null);
		assertThrows(ConflictException.class, () -> facade.refundOrder(store, 9L, null, language));
	}

	@Test
	void fullyRefundedIsConflict() throws Exception {
		refundable("0");
		assertThrows(ConflictException.class, () -> facade.refundOrder(store, 9L, null, language));
	}

	@Test
	void invalidAmountsAreBadRequest() throws Exception {
		refundable("40.00");
		for (String amount : new String[] { "0", "-5", "40.01" }) {
			RestApiException e = assertThrows(RestApiException.class,
					() -> facade.refundOrder(store, 9L, new BigDecimal(amount), language));
			assertEquals("400", e.getErrorCode());
		}
		verify(paymentService, never()).processRefund(any(), any(), any(), any());
	}

	@Test
	void missingAmountRefundsRemaining() throws Exception {
		refundable("40.00");
		// the populator is not wired in this test; only the call into the payment service matters
		when(paymentService.processRefund(order, null, store, new BigDecimal("40.00"))).thenReturn(null);
		try {
			facade.refundOrder(store, 9L, null, language);
		} catch (RuntimeException ignore) {
			// populating the readable transaction needs Spring wiring
		}
		verify(paymentService).processRefund(order, null, store, new BigDecimal("40.00"));
	}
}
