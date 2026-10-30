package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.OrderServiceImpl;
import com.salesmanager.core.modules.integration.PaymentOutcomeUnknownException;

/**
 * The payment outcome metric copies the rule OrderServiceImpl uses to decide whether a
 * payment error leaves the order PAYMENT_PENDING (unknown) or PAYMENT_FAILED. If the two
 * rules drift, the metric reports something other than what happened to the order, so
 * this test runs both on the same errors.
 */
public class PaymentOutcomeRuleParityTest {

	@Test
	public void metricAndOrderServiceClassifyErrorsTheSameWay() throws Exception {
		// package-private static in sm-core
		Method orderRule = OrderServiceImpl.class.getDeclaredMethod("isOutcomeUnknown", Throwable.class);
		orderRule.setAccessible(true);

		List<Throwable> errors = Arrays.asList(
				new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, "declined"),
				new ServiceException("wrapped timeout", new SocketTimeoutException("read timed out")),
				new ServiceException("wrapped unknown", new PaymentOutcomeUnknownException("no answer", new IOException("reset"))),
				new ServiceException(new IllegalStateException("bad state")),
				new PaymentOutcomeUnknownException("no answer", null),
				new IOException("connection reset"),
				new TimeoutException("gateway"),
				new IllegalArgumentException("bad card data"),
				new RuntimeException("wrapper", new ServiceException("declined")),
				new NullPointerException());

		for (Throwable e : errors) {
			boolean expected = (Boolean) orderRule.invoke(null, e);
			assertEquals("rule differs for " + e, expected, PaymentOutcomeMetrics.isOutcomeUnknown(e));
		}
	}
}
