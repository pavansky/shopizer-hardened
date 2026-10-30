package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundListParams;

class Stripe3RefundListTest {

	private static Refund stripeRefund(String id, String status, long amount) {
		Refund re = new Refund();
		re.setId(id);
		re.setStatus(status);
		re.setAmount(amount);
		re.setCurrency("usd");
		re.setPaymentIntent("pi_1");
		return re;
	}

	@Test
	void onlySucceededRefundsOfTheChargeBecomeLedgerEntries() throws Exception {
		final RefundListParams[] asked = new RefundListParams[1];
		Stripe3Payment stripe = new Stripe3Payment() {
			@Override
			protected List<Refund> listRefunds(RefundListParams params, RequestOptions options) {
				asked[0] = params;
				return Arrays.asList(stripeRefund("re_ok", "succeeded", 2550L), stripeRefund("re_wait", "pending", 100L),
						stripeRefund("re_bad", "failed", 100L));
			}
		};
		IntegrationConfiguration configuration = new IntegrationConfiguration();
		configuration.setIntegrationKeys(Collections.singletonMap("secretKey", "sk_test_x"));

		List<Transaction> refunds = stripe.succeededRefunds(configuration, "ch_1");

		assertEquals("ch_1", asked[0].getCharge());
		assertEquals(1, refunds.size());
		Transaction t = refunds.get(0);
		assertEquals(TransactionType.REFUND, t.getTransactionType());
		assertEquals(new BigDecimal("25.50"), t.getAmount());
		assertEquals("re_ok", t.getTransactionDetails().get("TRANSACTIONID"));
		assertEquals("pi_1", t.getTransactionDetails().get("REFUNDEDTRANSACTION"));
	}

	@Test
	void displayAmountUsesStripeMinorUnits() {
		assertEquals("12.34 USD", Stripe3Payment.displayAmount(1234L, "usd"));
		assertEquals("500 JPY", Stripe3Payment.displayAmount(500L, "jpy"));
	}
}
