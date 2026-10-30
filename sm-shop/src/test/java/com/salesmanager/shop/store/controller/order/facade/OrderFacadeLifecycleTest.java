package com.salesmanager.shop.store.controller.order.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.OrderCheckoutSteps;
import com.salesmanager.core.business.services.order.OrderCommandRefusedException;
import com.salesmanager.core.business.services.order.OrderCommandService;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderReconciler;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.business.utils.CoreConfiguration;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.model.order.ReadableOrderLifecycle;
import com.salesmanager.shop.model.order.history.PersistableOrderStatusHistory;
import com.salesmanager.shop.store.api.exception.ConflictException;
import com.salesmanager.shop.store.api.exception.GenericRuntimeException;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.utils.EmailTemplatesUtils;
import com.salesmanager.shop.utils.LabelUtils;

/** Status updates, cancel, refund restock, payment re-check and lifecycle emails. */
class OrderFacadeLifecycleTest {

	private OrderFacadeImpl facade;
	private OrderService orderService;
	private TransactionService transactionService;
	private PaymentService paymentService;
	private OrderCommandService commands;
	private OrderCheckoutSteps steps;
	private PendingOrderReconciler reconciler;
	private EmailTemplatesUtils emails;
	private CustomerService customerService;
	private final MerchantStore store = new MerchantStore();
	private final Language language = new Language();
	private final Order order = new Order();

	@BeforeEach
	void setUp() throws Exception {
		language.setCode("en");
		store.setId(1);
		store.setCode("DEFAULT");
		store.setDefaultLanguage(language);
		order.setId(9L);
		order.setCustomerId(3L);
		order.setStatus(OrderStatus.ORDERED);
		order.setCustomerEmailAddress("buyer@example.com");
		facade = new OrderFacadeImpl();
		orderService = mock(OrderService.class);
		transactionService = mock(TransactionService.class);
		paymentService = mock(PaymentService.class);
		commands = mock(OrderCommandService.class);
		steps = mock(OrderCheckoutSteps.class);
		reconciler = mock(PendingOrderReconciler.class);
		emails = mock(EmailTemplatesUtils.class);
		CoreConfiguration configuration = mock(CoreConfiguration.class);
		when(configuration.getProperty("ORDER_EMAIL_API")).thenReturn("true");
		LabelUtils messages = mock(LabelUtils.class);
		when(messages.getMessage(anyString(), any(String[].class), any())).thenReturn("Order status changed");
		inject("orderService", orderService);
		inject("transactionService", transactionService);
		inject("paymentService", paymentService);
		customerService = mock(CustomerService.class);
		inject("customerService", customerService);
		inject("orderCommandService", commands);
		inject("orderCheckoutSteps", steps);
		inject("pendingOrderReconciler", reconciler);
		inject("emailTemplatesUtils", emails);
		inject("coreConfiguration", configuration);
		inject("messages", messages);
		when(orderService.getOrder(9L, store)).thenReturn(order);
		when(transactionService.getRefundableAmount(any())).thenReturn(BigDecimal.ZERO);
		when(steps.markStatus(anyLong(), any(), any(), anyString(), anyBoolean())).thenReturn(true);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = OrderFacadeImpl.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(facade, value);
	}

	private void verifyStatusEmail() {
		verify(emails).sendUpdateOrderStatusEmail(any(), eq(order), any(OrderStatusHistory.class), eq(store), any(),
				any());
	}

	@Test
	void statusUpdateRefusesMovesThatMustMoveMoney() throws Exception {
		ConflictException cancel = assertThrows(ConflictException.class,
				() -> facade.updateOrderStatus(order, OrderStatus.CANCELED, store));
		assertTrue(cancel.getErrorMessage().contains("/cancel"));
		ConflictException refund = assertThrows(ConflictException.class,
				() -> facade.updateOrderStatus(order, OrderStatus.REFUNDED, store));
		assertTrue(refund.getErrorMessage().contains("/refund"));
		assertThrows(ConflictException.class, () -> facade.updateOrderStatus(order, OrderStatus.PAYMENT_FAILED, store));

		assertEquals(OrderStatus.ORDERED, order.getStatus());
		verify(steps, never()).markStatus(anyLong(), any(), any(), anyString(), anyBoolean());
		verify(emails, never()).sendUpdateOrderStatusEmail(any(), any(), any(), any(), any(), any());
	}

	@Test
	void fulfilmentMoveIsSavedAndEmailed() throws Exception {
		facade.updateOrderStatus(order, OrderStatus.PROCESSED, store);

		assertEquals(OrderStatus.PROCESSED, order.getStatus());
		// compare-and-set from the status that was read, history in the same step
		verify(steps).markStatus(9L, OrderStatus.ORDERED, OrderStatus.PROCESSED, "Order status changed", true);
		verify(orderService, never()).addOrderStatusHistory(any(), any());
		verifyStatusEmail();
	}

	@Test
	void statusUpdateThatLostARaceIsRefusedAndNotEmailed() throws Exception {
		// a cancel committed CANCELED after this request read the order as ORDERED
		when(steps.markStatus(9L, OrderStatus.ORDERED, OrderStatus.DELIVERED, "Order status changed", true))
				.thenReturn(false);

		ConflictException refused = assertThrows(ConflictException.class,
				() -> facade.updateOrderStatus(order, OrderStatus.DELIVERED, store));

		assertTrue(refused.getErrorMessage().contains("changed while"), refused.getErrorMessage());
		assertEquals(OrderStatus.ORDERED, order.getStatus());
		verify(emails, never()).sendUpdateOrderStatusEmail(any(), any(), any(), any(), any(), any());
	}

	@Test
	void manualPaymentConfirmationGoesThroughTheCommandWithTheAdminUser() throws Exception {
		order.setStatus(OrderStatus.PAYMENT_REVIEW);
		when(commands.confirmPayment(order, "admin@shopizer.com", "MO-1", "money order received"))
				.thenAnswer(inv -> {
					order.setStatus(OrderStatus.ORDERED);
					return "payment confirmed by hand";
				});

		ReadableOrderLifecycle l = facade.confirmPayment(9L, "admin@shopizer.com", "MO-1", "money order received",
				store);

		assertEquals("ORDERED", l.getStatus());
		assertTrue(l.getMessage().startsWith("Payment confirmed"), l.getMessage());
	}

	@Test
	void manualPaymentConfirmationNeedsAReasonAndAReviewOrder() throws Exception {
		assertThrows(com.salesmanager.shop.store.api.exception.RestApiException.class,
				() -> facade.confirmPayment(9L, "admin@shopizer.com", null, " ", store));
		verify(commands, never()).confirmPayment(any(), any(), any(), any());

		when(commands.confirmPayment(eq(order), any(), any(), any()))
				.thenThrow(new OrderCommandRefusedException("only PAYMENT_REVIEW"));
		assertThrows(ConflictException.class, () -> facade.confirmPayment(9L, "admin@shopizer.com", null, "x", store));
	}

	@Test
	void historyNoteCannotChangeTheStatus() throws Exception {
		PersistableOrderStatusHistory note = new PersistableOrderStatusHistory();
		note.setStatus(OrderStatus.DELIVERED);
		note.setComments("shipped");

		assertThrows(ConflictException.class, () -> facade.createOrderStatus(note, 9L, store));
		verify(orderService, never()).addOrderStatusHistory(any(), any());

		note.setStatus(null);
		facade.createOrderStatus(note, 9L, store);
		ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
		verify(orderService).addOrderStatusHistory(eq(order), history.capture());
		assertEquals(OrderStatus.ORDERED, history.getValue().getStatus());
		assertEquals("shipped", history.getValue().getComments());
	}

	/** processRefund sets REFUNDED on the order it was given when, under its lock, the refund emptied the order. */
	private void refundEmptiesTheOrder() throws Exception {
		when(paymentService.processRefund(eq(order), any(), eq(store), any())).thenAnswer(inv -> {
			order.setStatus(OrderStatus.REFUNDED);
			return new Transaction();
		});
	}

	@Test
	void fullRefundReturnsStockAndEmails() throws Exception {
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("40.00"));
		refundEmptiesTheOrder();
		try {
			facade.refundOrder(store, 9L, null, language);
		} catch (RuntimeException ignore) {
			// populating the readable transaction needs Spring wiring
		}
		verify(paymentService).processRefund(order, null, store, new BigDecimal("40.00"));
		verify(steps).restock(9L);
		verifyStatusEmail();
	}

	@Test
	void partialRefundKeepsStock() throws Exception {
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("40.00"));
		try {
			facade.refundOrder(store, 9L, new BigDecimal("10.00"), language);
		} catch (RuntimeException ignore) {
			// populating the readable transaction needs Spring wiring
		}
		verify(paymentService).processRefund(order, null, store, new BigDecimal("10.00"));
		verify(steps, never()).restock(any());
		verifyStatusEmail();
	}

	@Test
	void partialRefundThatEmptiedTheOrderReturnsStock() throws Exception {
		// 249 left when read, but a concurrent refund of 100 committed first: under the
		// lock this 149 is the refund that empties the order
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("249.00"));
		refundEmptiesTheOrder();
		try {
			facade.refundOrder(store, 9L, new BigDecimal("149.00"), language);
		} catch (RuntimeException ignore) {
			// populating the readable transaction needs Spring wiring
		}
		verify(steps).restock(9L);
	}

	@Test
	void fullAmountThatEndedPartialKeepsStock() throws Exception {
		// asked for everything that was left when read; processRefund saw it as partial
		// (it does not set REFUNDED), so another refund will be the one that restocks
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("40.00"));
		try {
			facade.refundOrder(store, 9L, null, language);
		} catch (RuntimeException ignore) {
			// populating the readable transaction needs Spring wiring
		}
		verify(steps, never()).restock(any());
	}

	@Test
	void statusUpdateCannotSettleAnUnpaidOrder() throws Exception {
		for (OrderStatus waiting : Arrays.asList(OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_REVIEW)) {
			order.setStatus(waiting);
			for (OrderStatus target : Arrays.asList(OrderStatus.ORDERED, OrderStatus.PROCESSED)) {
				ConflictException refused = assertThrows(ConflictException.class,
						() -> facade.updateOrderStatus(order, target, store));
				assertTrue(refused.getErrorMessage().contains("/payment/recheck"), refused.getErrorMessage());
				assertEquals(waiting, order.getStatus());
			}
		}
		verify(steps, never()).markStatus(anyLong(), any(), any(), anyString(), anyBoolean());
		verify(emails, never()).sendUpdateOrderStatusEmail(any(), any(), any(), any(), any(), any());
	}

	@Test
	void reviewOrderOffersRecheckAndCancelButNoStatusUpdate() {
		order.setStatus(OrderStatus.PAYMENT_REVIEW);

		ReadableOrderLifecycle l = facade.getOrderLifecycle(9L, store);

		assertTrue(l.getStatusUpdates().isEmpty());
		assertTrue(l.isPaymentRecheck());
		assertTrue(l.isPaymentConfirm());
		assertTrue(l.isCancelable());
	}

	@Test
	void customerNotNotifiedWithoutAnEmailAddress() throws Exception {
		order.setCustomerEmailAddress(null);
		when(customerService.getById(3L)).thenReturn(new com.salesmanager.core.model.customer.Customer());

		facade.updateOrderStatus(order, OrderStatus.PROCESSED, store);

		verify(steps).markStatus(9L, OrderStatus.ORDERED, OrderStatus.PROCESSED, "Order status changed", false);
	}

	@Test
	void transitionsListLegalMovesAndWhatTheStatusUpdateAccepts() {
		ReadableOrderLifecycle l = facade.getOrderLifecycle(9L, store);

		assertEquals("ORDERED", l.getStatus());
		assertEquals(Arrays.asList("PROCESSED", "DELIVERED", "REFUNDED", "CANCELED"), l.getNext());
		assertEquals(Arrays.asList("PROCESSED", "DELIVERED"), l.getStatusUpdates());
		assertTrue(l.isCancelable());
		assertFalse(l.isPaymentRecheck());
	}

	@Test
	void orderOfAnotherStoreIsNotFound() {
		assertThrows(ResourceNotFoundException.class, () -> facade.getOrderLifecycle(77L, store));
		assertThrows(ResourceNotFoundException.class, () -> facade.cancelOrder(77L, null, store));
		assertThrows(ResourceNotFoundException.class, () -> facade.recheckPayment(77L, store));
	}

	@Test
	void cancelRunsTheCommandAndEmails() throws Exception {
		when(commands.cancel(order, store, "asked")).thenReturn("Canceled, refunded 40.00 CAD. asked");

		ReadableOrderLifecycle l = facade.cancelOrder(9L, "asked", store);

		assertEquals("Canceled, refunded 40.00 CAD. asked", l.getMessage());
		ArgumentCaptor<OrderStatusHistory> change = ArgumentCaptor.forClass(OrderStatusHistory.class);
		verify(emails).sendUpdateOrderStatusEmail(any(), eq(order), change.capture(), eq(store), any(), any());
		assertEquals("Canceled, refunded 40.00 CAD. asked", change.getValue().getComments());
	}

	@Test
	void secondCancelIsAConflict() throws Exception {
		when(commands.cancel(order, store, null)).thenThrow(new OrderCommandRefusedException("already canceled"));

		assertThrows(ConflictException.class, () -> facade.cancelOrder(9L, null, store));
		verify(emails, never()).sendUpdateOrderStatusEmail(any(), any(), any(), any(), any(), any());
	}

	@Test
	void recheckOnlyForOrdersWaitingForPayment() {
		assertThrows(ConflictException.class, () -> facade.recheckPayment(9L, store));
		verify(reconciler, never()).recheckOrder(any());
	}

	@Test
	void recheckReportsTheOutcome() {
		order.setStatus(OrderStatus.PAYMENT_REVIEW);
		PendingOrderReconciler.Result result = new PendingOrderReconciler.Result();
		result.finalised = 1;
		when(reconciler.recheckOrder(9L)).thenAnswer(inv -> {
			order.setStatus(OrderStatus.PROCESSED);
			return result;
		});

		ReadableOrderLifecycle l = facade.recheckPayment(9L, store);

		assertEquals("PROCESSED", l.getStatus());
		assertTrue(l.getMessage().startsWith("Payment confirmed"));
	}

	@Test
	void recheckWithoutAGatewayAnswerIsAnError() {
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		PendingOrderReconciler.Result result = new PendingOrderReconciler.Result();
		result.lookupErrors = 1;
		when(reconciler.recheckOrder(9L)).thenReturn(result);

		assertThrows(GenericRuntimeException.class, () -> facade.recheckPayment(9L, store));
	}

	@Test
	void recheckThatMovedTheOrderToReviewIsNotAnError() {
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		PendingOrderReconciler.Result result = new PendingOrderReconciler.Result();
		result.lookupErrors = 1;
		result.review = 1;
		when(reconciler.recheckOrder(9L)).thenAnswer(inv -> {
			order.setStatus(OrderStatus.PAYMENT_REVIEW);
			return result;
		});

		ReadableOrderLifecycle l = facade.recheckPayment(9L, store);

		assertEquals("PAYMENT_REVIEW", l.getStatus());
		assertTrue(l.getMessage().contains("PAYMENT_REVIEW"), l.getMessage());
	}
}
