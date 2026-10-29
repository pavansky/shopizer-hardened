package com.salesmanager.test.business.modules.integration.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Result;
import com.braintreegateway.TransactionGateway;
import com.braintreegateway.Transaction.Status;
import com.salesmanager.core.business.modules.integration.payment.impl.BraintreePayment;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;

public class BraintreeRefundTest {

	private static final BigDecimal AMOUNT = new BigDecimal("10.00");

	private TransactionGateway transactionGateway;
	private BraintreePayment braintree;
	private IntegrationConfiguration configuration;
	private Transaction captured;

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

		captured = new Transaction();
		captured.setTransactionType(TransactionType.AUTHORIZECAPTURE);
		captured.getTransactionDetails().put("TRANSACTIONID", "bt123");
	}

	private void gatewayStatus(Status status) {
		com.braintreegateway.Transaction found = mock(com.braintreegateway.Transaction.class);
		when(found.getStatus()).thenReturn(status);
		when(transactionGateway.find("bt123")).thenReturn(found);
	}

	@SuppressWarnings("unchecked")
	private Result<com.braintreegateway.Transaction> success(String id) {
		com.braintreegateway.Transaction target = mock(com.braintreegateway.Transaction.class);
		when(target.getId()).thenReturn(id);
		Result<com.braintreegateway.Transaction> result = mock(Result.class);
		when(result.isSuccess()).thenReturn(true);
		when(result.getTarget()).thenReturn(target);
		return result;
	}

	private Transaction refund(boolean partial) throws IntegrationException {
		return braintree.refund(partial, null, captured, new Order(), AMOUNT, configuration, null);
	}

	@Test
	public void authorizedFullRefundIsVoided() throws Exception {
		gatewayStatus(Status.AUTHORIZED);
		Result<com.braintreegateway.Transaction> voided = success("bt123");
		when(transactionGateway.voidTransaction("bt123")).thenReturn(voided);

		Transaction trx = refund(false);

		verify(transactionGateway).voidTransaction("bt123");
		verify(transactionGateway, never()).refund(anyString(), any(BigDecimal.class));
		assertEquals(TransactionType.REFUND, trx.getTransactionType());
		assertEquals(AMOUNT, trx.getAmount());
		assertEquals("VOIDED", trx.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	public void submittedForSettlementFullRefundIsVoided() throws Exception {
		gatewayStatus(Status.SUBMITTED_FOR_SETTLEMENT);
		Result<com.braintreegateway.Transaction> voided = success("bt123");
		when(transactionGateway.voidTransaction("bt123")).thenReturn(voided);

		refund(false);

		verify(transactionGateway).voidTransaction("bt123");
	}

	@Test
	public void settledIsRefunded() throws Exception {
		gatewayStatus(Status.SETTLED);
		Result<com.braintreegateway.Transaction> refunded = success("rf456");
		when(transactionGateway.refund("bt123", AMOUNT)).thenReturn(refunded);

		Transaction trx = refund(true);

		verify(transactionGateway).refund("bt123", AMOUNT);
		verify(transactionGateway, never()).voidTransaction(anyString());
		assertEquals("rf456", trx.getTransactionDetails().get("TRANSACTIONID"));
		assertEquals("REFUNDED", trx.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	public void settlingIsRefunded() throws Exception {
		gatewayStatus(Status.SETTLING);
		Result<com.braintreegateway.Transaction> refunded = success("rf456");
		when(transactionGateway.refund("bt123", AMOUNT)).thenReturn(refunded);

		refund(false);

		verify(transactionGateway).refund("bt123", AMOUNT);
	}

	@Test
	public void partialRefundOfUnsettledIsRejected() {
		gatewayStatus(Status.AUTHORIZED);
		assertFails(true, "can only be voided");
	}

	@Test
	public void otherStatusesAreRejected() {
		gatewayStatus(Status.VOIDED);
		assertFails(false, "in status VOIDED");
	}

	private void assertFails(boolean partial, String message) {
		try {
			refund(partial);
			fail("expected IntegrationException");
		} catch (IntegrationException e) {
			assertEquals(IntegrationException.TRANSACTION_EXCEPTION, e.getErrorCode());
			assertTrue(e.getMessage(), e.getMessage().contains(message));
		}
		verify(transactionGateway, never()).voidTransaction(anyString());
		verify(transactionGateway, never()).refund(anyString(), any(BigDecimal.class));
	}
}
