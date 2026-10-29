package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;

public class Stripe3RefundTest {

	@Test
	@SuppressWarnings("unchecked")
	public void refundIsRecordedAsRefundWithGatewayIdAndActualAmount() throws Exception {
		final Map<String, Object>[] sent = new Map[1];
		Stripe3Payment stripe = new Stripe3Payment() {
			@Override
			protected Refund createRefund(Map<String, Object> params, RequestOptions requestOptions) {
				sent[0] = params;
				Refund refund = new Refund();
				refund.setId("re_789");
				refund.setStatus("succeeded");
				return refund;
			}
		};

		MerchantStore store = new MerchantStore();
		Currency currency = new Currency();
		currency.setCurrency(java.util.Currency.getInstance("USD"));
		store.setCurrency(currency);

		IntegrationConfiguration configuration = new IntegrationConfiguration();
		configuration.setIntegrationKeys(Collections.singletonMap("secretKey", "sk_test_x"));

		Transaction captured = new Transaction();
		captured.setTransactionType(TransactionType.CAPTURE);
		captured.getTransactionDetails().put("TRNORDERNUMBER", "pi_123");

		Order order = new Order();
		order.setTotal(new BigDecimal("100.00"));

		Transaction trx = stripe.refund(true, store, captured, order, new BigDecimal("25.50"), configuration, null);

		assertEquals(TransactionType.REFUND, trx.getTransactionType());
		assertEquals(new BigDecimal("25.50"), trx.getAmount());
		assertEquals("re_789", trx.getTransactionDetails().get("TRANSACTIONID"));
		assertEquals("pi_123", trx.getTransactionDetails().get("REFUNDEDTRANSACTION"));
		assertNotNull(sent[0]);
		assertEquals("pi_123", sent[0].get("payment_intent"));
		assertEquals(2550L, sent[0].get("amount"));
	}
}
