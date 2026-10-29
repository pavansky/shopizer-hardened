package com.salesmanager.core.business.services.payments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;

public class PaymentServiceRefundTest {

	private static final String MODULE = "stripe3";

	private PaymentServiceImpl service;
	private TransactionService transactionService;
	private OrderService orderService;
	private PaymentModule paymentModule;
	private final MerchantStore store = new MerchantStore();
	private Order order;
	private Transaction captured;

	@Before
	public void setUp() throws Exception {
		service = spy(new PaymentServiceImpl());
		transactionService = mock(TransactionService.class);
		orderService = mock(OrderService.class);
		paymentModule = mock(PaymentModule.class);

		inject("transactionService", transactionService);
		inject("orderService", orderService);
		Map<String, PaymentModule> modules = new HashMap<String, PaymentModule>();
		modules.put(MODULE, paymentModule);
		inject("paymentModules", modules);

		IntegrationConfiguration configuration = new IntegrationConfiguration();
		configuration.setActive(true);
		Map<String, IntegrationConfiguration> configured = new HashMap<String, IntegrationConfiguration>();
		configured.put(MODULE, configuration);
		doReturn(configured).when(service).getPaymentModulesConfigured(store);
		doReturn(null).when(service).getPaymentMethodByCode(store, MODULE);

		order = new Order();
		order.setId(5L);
		order.setPaymentModuleCode(MODULE);
		order.setTotal(new BigDecimal("100.00"));
		order.setStatus(OrderStatus.PROCESSED);

		captured = new Transaction();
		captured.setTransactionType(TransactionType.CAPTURE);
		captured.setAmount(new BigDecimal("100.00"));
		when(transactionService.getRefundableTransaction(order)).thenReturn(captured);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = PaymentServiceImpl.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(service, value);
	}

	private void remaining(String amount) throws ServiceException {
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal(amount));
	}

	private void moduleReturns(TransactionType recordedAs) throws Exception {
		Transaction gatewayResult = new Transaction();
		gatewayResult.setTransactionType(recordedAs);
		gatewayResult.getTransactionDetails().put("TRANSACTIONID", "re_123");
		when(paymentModule.refund(anyBoolean(), eq(store), eq(captured), eq(order), any(BigDecimal.class), any(), any()))
				.thenReturn(gatewayResult);
	}

	private void assertRejected(String amount) throws Exception {
		try {
			service.processRefund(order, null, store, new BigDecimal(amount));
			fail("expected validation failure for " + amount);
		} catch (ServiceException e) {
			assertEquals(ServiceException.EXCEPTION_VALIDATION, e.getExceptionType());
		}
		verify(paymentModule, never()).refund(anyBoolean(), any(), any(), any(), any(), any(), any());
	}

	@Test
	public void rejectsZeroNegativeAndMoreThanRemaining() throws Exception {
		remaining("40.00");
		assertRejected("0");
		assertRejected("-1.00");
		assertRejected("40.01");
	}

	@Test
	public void fullRefundRecordsRefundAndMovesOrderToRefunded() throws Exception {
		remaining("100.00");
		moduleReturns(TransactionType.CAPTURE);

		Transaction result = service.processRefund(order, null, store, new BigDecimal("100.00"));

		ArgumentCaptor<Transaction> recorded = ArgumentCaptor.forClass(Transaction.class);
		verify(transactionService).create(recorded.capture());
		assertEquals(TransactionType.REFUND, recorded.getValue().getTransactionType());
		assertEquals(new BigDecimal("100.00"), recorded.getValue().getAmount());
		assertEquals("re_123", result.getTransactionDetails().get("TRANSACTIONID"));
		verify(paymentModule).refund(eq(false), eq(store), eq(captured), eq(order), eq(new BigDecimal("100.00")), any(), any());

		assertEquals(OrderStatus.REFUNDED, order.getStatus());
		assertEquals(new BigDecimal("100.00"), order.getTotal());
		OrderStatusHistory history = order.getOrderHistory().iterator().next();
		assertEquals(OrderStatus.REFUNDED, history.getStatus());
		verify(orderService).saveOrUpdate(order);
	}

	@Test
	public void partialRefundKeepsStatusAndAddsComment() throws Exception {
		remaining("100.00");
		moduleReturns(TransactionType.REFUND);

		service.processRefund(order, null, store, new BigDecimal("30.00"));

		verify(paymentModule).refund(eq(true), eq(store), eq(captured), eq(order), eq(new BigDecimal("30.00")), any(), any());
		assertEquals(OrderStatus.PROCESSED, order.getStatus());
		assertEquals(new BigDecimal("100.00"), order.getTotal());
		assertEquals(1, order.getOrderHistory().size());
		OrderStatusHistory history = order.getOrderHistory().iterator().next();
		assertEquals(OrderStatus.PROCESSED, history.getStatus());
		assertTrue(history.getComments().contains("Partial refund of 30.00"));
		assertTrue(history.getComments().contains("remaining refundable 70.00"));
	}

	@Test
	public void lastPartialRefundCompletesTheRefund() throws Exception {
		remaining("70.00");
		moduleReturns(TransactionType.REFUND);

		service.processRefund(order, null, store, new BigDecimal("70.00"));

		assertEquals(OrderStatus.REFUNDED, order.getStatus());
	}

	@Test
	public void nothingCapturedIsRejected() throws Exception {
		when(transactionService.getRefundableTransaction(order)).thenReturn(null);
		try {
			service.processRefund(order, null, store, new BigDecimal("1.00"));
			fail("expected failure");
		} catch (ServiceException e) {
			assertEquals(ServiceException.EXCEPTION_VALIDATION, e.getExceptionType());
		}
	}

	@Test
	public void legacySaleMapsToAuthorizeCapture() {
		assertEquals(TransactionType.AUTHORIZECAPTURE, PaymentServiceImpl.resolveTransactionType("SALE"));
		assertEquals(TransactionType.AUTHORIZECAPTURE, PaymentServiceImpl.resolveTransactionType("sale"));
		assertEquals(TransactionType.AUTHORIZE, PaymentServiceImpl.resolveTransactionType("AUTHORIZE"));
		assertEquals(TransactionType.AUTHORIZECAPTURE, PaymentServiceImpl.resolveTransactionType(null));
		assertEquals(TransactionType.AUTHORIZECAPTURE, PaymentServiceImpl.resolveTransactionType("BOGUS"));
	}
}
