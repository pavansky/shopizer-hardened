package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.TransactionGateway;
import com.braintreegateway.TransactionRequest;
import com.braintreegateway.exceptions.TimeoutException;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.system.IntegrationConfiguration;

class BraintreeTimeoutTest {

	private static IntegrationConfiguration configuration() {
		Map<String, String> keys = new HashMap<>();
		keys.put("merchant_id", "m");
		keys.put("public_key", "pub");
		keys.put("private_key", "priv");
		IntegrationConfiguration configuration = new IntegrationConfiguration();
		configuration.setIntegrationKeys(keys);
		configuration.setEnvironment("TEST");
		return configuration;
	}

	@Test
	void gatewayGetsExplicitConnectAndReadTimeouts() {
		BraintreeGateway gateway = new BraintreePayment().gateway(configuration());

		// braintree-java defaults to a 60 s read timeout and no connect timeout
		assertEquals(3000, gateway.getConfiguration().getConnectTimeout());
		assertEquals(15000, gateway.getConfiguration().getTimeout());
	}

	@Test
	void timeoutsComeFromProperties() {
		BraintreePayment module = new BraintreePayment();
		ReflectionTestUtils.setField(module, "connectTimeoutMs", 1000);
		ReflectionTestUtils.setField(module, "readTimeoutMs", 4000);

		BraintreeGateway gateway = module.gateway(configuration());

		assertEquals(1000, gateway.getConfiguration().getConnectTimeout());
		assertEquals(4000, gateway.getConfiguration().getTimeout());
	}

	@Test
	void readTimeoutIsNotTurnedIntoADecline() {
		BraintreeGateway gateway = mock(BraintreeGateway.class);
		TransactionGateway transactions = mock(TransactionGateway.class);
		when(gateway.transaction()).thenReturn(transactions);
		when(transactions.sale(any(TransactionRequest.class))).thenThrow(
				new TimeoutException("Read timed out", new SocketTimeoutException("Read timed out")));
		BraintreePayment module = new BraintreePayment() {
			@Override
			protected BraintreeGateway gateway(IntegrationConfiguration cfg) {
				return gateway;
			}
		};
		Payment payment = new Payment();
		payment.setPaymentMetaData(new HashMap<>(Collections.singletonMap("paymentToken", "nonce")));

		Exception thrown = assertThrows(Exception.class, () -> module.authorizeAndCapture(null, null,
				null, BigDecimal.TEN, payment, configuration(), null));

		// Not a ServiceException (a decline) and caused by an IO timeout: checkout keeps the
		// order PAYMENT_PENDING, answers 202 and lets the reconciler ask Braintree.
		assertFalse(thrown instanceof ServiceException);
		assertTrue(thrown instanceof TimeoutException);
		assertTrue(thrown.getCause() instanceof SocketTimeoutException);
	}
}
