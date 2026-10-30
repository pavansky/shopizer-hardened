package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class PaymentOutcomeMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final PaymentService target = mock(PaymentService.class);
	private PaymentService proxied;
	private MerchantStore store;

	@Before
	public void setUp() {
		PaymentOutcomeMetrics metrics = new PaymentOutcomeMetrics(registry);
		metrics.setPaymentModules(Collections.singletonMap("moneyorder", mock(PaymentModule.class)));
		AspectJProxyFactory factory = new AspectJProxyFactory(target);
		factory.addAspect(metrics);
		proxied = factory.getProxy();
		store = new MerchantStore();
		store.setCode("DEFAULT");
	}

	@Test
	public void countsSuccessAndReturnsTheTransaction() throws Exception {
		Transaction tx = new Transaction();
		when(target.processPayment(any(), any(), any(), any(), any())).thenReturn(tx);
		assertSame(tx, pay("moneyorder"));
		assertEquals(1.0, count("DEFAULT", "moneyorder", "success"), 0);
	}

	@Test
	public void serviceExceptionIsAFailureAndIsRethrown() throws Exception {
		ServiceException declined = new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, "declined");
		when(target.processPayment(any(), any(), any(), any(), any())).thenThrow(declined);
		try {
			pay("moneyorder");
			fail("expected the decline to propagate");
		} catch (ServiceException e) {
			assertSame(declined, e);
		}
		assertEquals(1.0, count("DEFAULT", "moneyorder", "failed"), 0);
	}

	@Test
	public void timeoutOrUnexpectedErrorIsUnknown() throws Exception {
		when(target.processPayment(any(), any(), any(), any(), any()))
				.thenThrow(new ServiceException(new SocketTimeoutException("read timed out")))
				.thenThrow(new IllegalStateException("gateway sdk bug"));
		for (int i = 0; i < 2; i++) {
			try {
				pay("moneyorder");
				fail();
			} catch (Exception expected) {
				// rethrown
			}
		}
		assertEquals(2.0, count("DEFAULT", "moneyorder", "unknown"), 0);
	}

	@Test
	public void unregisteredModuleNameIsOtherSoTheLabelStaysBounded() throws Exception {
		when(target.processPayment(any(), any(), any(), any(), any())).thenThrow(new ServiceException("not configured"));
		try {
			pay("made-up-" + System.nanoTime());
		} catch (ServiceException expected) {
			// rethrown
		}
		assertEquals(1.0, count("DEFAULT", "other", "failed"), 0);
	}

	private Transaction pay(String module) throws ServiceException {
		Payment payment = new Payment();
		payment.setModuleName(module);
		return proxied.processPayment(null, store, payment, Collections.emptyList(), new Order());
	}

	private double count(String store, String module, String outcome) {
		return registry.get(PaymentOutcomeMetrics.NAME).tags("store", store, "module", module, "outcome", outcome)
				.counter().count();
	}
}
