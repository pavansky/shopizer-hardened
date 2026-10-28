package com.salesmanager.test.business.modules.integration.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Result;
import com.braintreegateway.TransactionGateway;
import com.braintreegateway.TransactionRequest;
import com.braintreegateway.ValidationErrors;
import com.salesmanager.core.business.modules.integration.payment.impl.BraintreePayment;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;

public class BraintreePaymentTest {

	private TransactionGateway transactionGateway;
	private BraintreePayment braintree;
	private IntegrationConfiguration configuration;
	private Payment payment;

	@Before
	public void setUp() {
		final BraintreeGateway gateway = mock(BraintreeGateway.class);
		transactionGateway = mock(TransactionGateway.class);
		when(gateway.transaction()).thenReturn(transactionGateway);

		braintree = new BraintreePayment() {
			@Override
			protected BraintreeGateway gateway(IntegrationConfiguration cfg) {
				return gateway;
			}
		};

		Map<String, String> keys = new HashMap<String, String>();
		keys.put("merchant_id", "m");
		keys.put("public_key", "pub");
		keys.put("private_key", "priv");
		configuration = new IntegrationConfiguration();
		configuration.setIntegrationKeys(keys);
		configuration.setEnvironment("TEST");

		payment = new Payment();
		payment.setPaymentMetaData(Collections.singletonMap("paymentToken", "fake-valid-nonce"));
	}

	@SuppressWarnings("unchecked")
	private void saleReturns(com.braintreegateway.Transaction.Status status) {
		com.braintreegateway.Transaction target = mock(com.braintreegateway.Transaction.class);
		when(target.getId()).thenReturn("bt123");
		when(target.getStatus()).thenReturn(status);
		Result<com.braintreegateway.Transaction> result = mock(Result.class);
		when(result.isSuccess()).thenReturn(true);
		when(result.getTarget()).thenReturn(target);
		when(transactionGateway.sale(any(TransactionRequest.class))).thenReturn(result);
	}

	private Transaction authorizeAndCapture() throws IntegrationException {
		return braintree.authorizeAndCapture(null, null, new ArrayList<>(), new BigDecimal("10.00"), payment,
				configuration, null);
	}

	@Test
	public void saleIsSubmittedForSettlement() throws Exception {
		saleReturns(com.braintreegateway.Transaction.Status.SUBMITTED_FOR_SETTLEMENT);

		Transaction trx = authorizeAndCapture();

		ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
		verify(transactionGateway).sale(captor.capture());
		assertTrue(captor.getValue().toXML()
				.contains("<submit-for-settlement type=\"boolean\">true</submit-for-settlement>"));
		assertEquals(TransactionType.AUTHORIZECAPTURE, trx.getTransactionType());
		assertEquals("SUBMITTED_FOR_SETTLEMENT", trx.getTransactionDetails().get("TRNAPPROVED"));
		assertEquals("bt123", trx.getTransactionDetails().get("TRANSACTIONID"));
	}

	@Test
	public void authorizedOnlySaleIsRecordedAsAuthorize() throws Exception {
		saleReturns(com.braintreegateway.Transaction.Status.AUTHORIZED);

		Transaction trx = authorizeAndCapture();

		assertEquals(TransactionType.AUTHORIZE, trx.getTransactionType());
		assertEquals("AUTHORIZED", trx.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void unsuccessfulSaleThrows() {
		Result<com.braintreegateway.Transaction> result = mock(Result.class);
		when(result.isSuccess()).thenReturn(false);
		when(result.getErrors()).thenReturn(new ValidationErrors());
		when(transactionGateway.sale(any(TransactionRequest.class))).thenReturn(result);

		try {
			authorizeAndCapture();
			fail("expected IntegrationException");
		} catch (IntegrationException e) {
			assertEquals(IntegrationException.TRANSACTION_EXCEPTION, e.getErrorCode());
		}
	}
}
