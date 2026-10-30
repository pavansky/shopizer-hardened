package com.salesmanager.core.business.services.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.modules.integration.payment.impl.MoneyOrderPayment;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentType;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;

class PaymentServiceBulkheadTest {

	private PaymentServiceImpl service;
	private PaymentModule stripe;
	private PaymentModule braintree;
	private TransactionService transactionService;
	private final MerchantStore store = new MerchantStore();
	private final ExecutorService pool = Executors.newFixedThreadPool(2);

	@BeforeEach
	void setUp() throws Exception {
		service = spy(new PaymentServiceImpl());
		stripe = mock(PaymentModule.class);
		braintree = mock(PaymentModule.class);
		transactionService = mock(TransactionService.class);
		Map<String, PaymentModule> modules = new HashMap<>();
		modules.put("stripe3", stripe);
		modules.put("braintree", braintree);
		modules.put("moneyorder", new MoneyOrderPayment());
		ReflectionTestUtils.setField(service, "paymentModules", modules);
		ReflectionTestUtils.setField(service, "transactionService", transactionService);
		ReflectionTestUtils.setField(service, "orderService", mock(OrderService.class));
		ReflectionTestUtils.setField(service, "bulkheadPermits", 1);

		Map<String, IntegrationConfiguration> configured = new HashMap<>();
		for (String code : modules.keySet()) {
			IntegrationConfiguration configuration = new IntegrationConfiguration();
			configuration.setActive(true);
			configuration.setModuleCode(code);
			configuration.setIntegrationKeys(new HashMap<>());
			configured.put(code, configuration);
		}
		doReturn(configured).when(service).getPaymentModulesConfigured(store);
		doReturn(null).when(service).getPaymentMethodByCode(any(), any());
		Currency usd = new Currency();
		usd.setCurrency(java.util.Currency.getInstance("USD"));
		store.setCurrency(usd);
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private static Payment payment(String module) {
		Payment payment = new Payment();
		payment.setModuleName(module);
		payment.setPaymentType(PaymentType.CREDITCARD);
		payment.setPaymentMetaData(new HashMap<>(Collections.singletonMap("paymentToken", "tok")));
		return payment;
	}

	private static Order order() {
		Order order = new Order();
		order.setTotal(new BigDecimal("10.00"));
		order.setStatus(OrderStatus.PAYMENT_PENDING);
		return order;
	}

	/** Starts a stripe3 charge that stays inside the gateway until the latch opens. */
	private Future<Transaction> chargeInFlight(CountDownLatch entered, CountDownLatch release) throws Exception {
		when(stripe.authorizeAndCapture(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return new Transaction();
		});
		Future<Transaction> inFlight = pool.submit(
				() -> service.processPayment(new Customer(), store, payment("stripe3"), null, order()));
		assertTrue(entered.await(5, TimeUnit.SECONDS));
		return inFlight;
	}

	@Test
	void fullBulkheadFailsFastBeforeTheGatewayIsCalled() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<Transaction> inFlight = chargeInFlight(entered, release);

		PaymentGatewayBusyException busy = assertThrows(PaymentGatewayBusyException.class,
				() -> service.processPayment(new Customer(), store, payment("stripe3"), null, order()));

		assertEquals("stripe3", busy.getModuleCode());
		assertEquals(2, busy.getRetryAfterSeconds());
		// only the first charge reached the module; the rejected one never did
		verify(stripe, org.mockito.Mockito.times(1)).authorizeAndCapture(any(), any(), any(), any(), any(), any(),
				any());
		release.countDown();
		inFlight.get(5, TimeUnit.SECONDS);
	}

	@Test
	void permitIsReturnedWhenTheCallEnds() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<Transaction> inFlight = chargeInFlight(entered, release);
		release.countDown();
		inFlight.get(5, TimeUnit.SECONDS);

		when(stripe.authorizeAndCapture(any(), any(), any(), any(), any(), any(), any())).thenReturn(new Transaction());
		service.processPayment(new Customer(), store, payment("stripe3"), null, order());
	}

	@Test
	void permitIsReturnedWhenTheGatewayFails() throws Exception {
		ReflectionTestUtils.setField(service, "bulkheadPermits", 1);
		AtomicInteger calls = new AtomicInteger();
		IntegrationException declined = new IntegrationException("declined");
		Transaction ok = new Transaction();

		assertThrows(IntegrationException.class, () -> service.callGateway("stripe3", stripe, () -> {
			calls.incrementAndGet();
			throw declined;
		}));
		assertSame(ok, service.callGateway("stripe3", stripe, () -> {
			calls.incrementAndGet();
			return ok;
		}));
		assertEquals(2, calls.get());
	}

	@Test
	void eachGatewayHasItsOwnLimit() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<Transaction> inFlight = chargeInFlight(entered, release);
		when(braintree.authorizeAndCapture(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new Transaction());

		// stripe3 is full, braintree is not affected
		service.processPayment(new Customer(), store, payment("braintree"), null, order());

		release.countDown();
		inFlight.get(5, TimeUnit.SECONDS);
	}

	@Test
	void moneyOrderIsNotLimited() throws Exception {
		ReflectionTestUtils.setField(service, "bulkheadPermits", 0);
		Payment moneyOrder = payment("moneyorder");
		moneyOrder.setPaymentType(PaymentType.MONEYORDER);

		Transaction tx = service.processPayment(new Customer(), store, moneyOrder, null, order());

		assertEquals(TransactionType.AUTHORIZECAPTURE, tx.getTransactionType());
	}

	@Test
	void refundAndCaptureAreLimitedToo() throws Exception {
		ReflectionTestUtils.setField(service, "bulkheadPermits", 0);
		Order order = order();
		order.setId(5L);
		order.setPaymentModuleCode("stripe3");
		order.setStatus(OrderStatus.PROCESSED);
		when(transactionService.getRefundableTransaction(order)).thenReturn(new Transaction());
		when(transactionService.getRefundableAmount(order)).thenReturn(new BigDecimal("10.00"));
		when(transactionService.getCapturableTransaction(order)).thenReturn(new Transaction());

		assertThrows(PaymentGatewayBusyException.class,
				() -> service.processRefund(order, null, store, new BigDecimal("10.00")));
		assertThrows(PaymentGatewayBusyException.class, () -> service.processCapturePayment(order, new Customer(), store));

		verify(stripe, never()).refund(org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any(), any(),
				any());
		verify(stripe, never()).capture(any(), any(), any(), any(), any(), any());
		verify(transactionService, never()).create(any());
	}

	@Test
	void busyIsADefiniteRefusalNotAnUnknownOutcome() {
		// A ServiceException means "no money moved" to checkout: the order is failed and the
		// stock released, unlike a timeout which keeps the order PAYMENT_PENDING.
		assertTrue(ServiceException.class.isAssignableFrom(PaymentGatewayBusyException.class));
	}
}
