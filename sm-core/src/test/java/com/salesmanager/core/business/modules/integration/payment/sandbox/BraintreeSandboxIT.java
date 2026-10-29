package com.salesmanager.core.business.modules.integration.payment.sandbox;

import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.attempt;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.newAttemptRef;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.payment;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.proof;
import static com.salesmanager.core.business.modules.integration.payment.sandbox.SandboxFixtures.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Environment;
import com.braintreegateway.Result;
import com.salesmanager.core.business.modules.integration.payment.impl.BraintreePayment;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus.Outcome;

/**
 * Braintree module against the real Braintree sandbox. Runs only with the three
 * BRAINTREE_SANDBOX_* variables set, through -Psandbox.
 */
@EnabledIfEnvironmentVariable(named = SandboxFixtures.BT_MERCHANT_VAR, matches = ".+")
@EnabledIfEnvironmentVariable(named = SandboxFixtures.BT_PUBLIC_VAR, matches = ".+")
@EnabledIfEnvironmentVariable(named = SandboxFixtures.BT_PRIVATE_VAR, matches = ".+")
class BraintreeSandboxIT {

	/** Sandbox amounts from 2000.00 up trigger processor declines; stay well below. */
	private static final BigDecimal AMOUNT = new BigDecimal("10.00");
	private static final String GW = "braintree";
	private static final long SEARCH_WAIT_MS = 60_000;

	private static IntegrationConfiguration configuration;
	/** Direct gateway, only for the sandbox-only settle call. */
	private static BraintreeGateway gateway;
	private final BraintreePayment module = new BraintreePayment();
	private final MerchantStore store = store();

	@BeforeAll
	static void keys() {
		configuration = SandboxFixtures.braintreeConfiguration();
		gateway = new BraintreeGateway(Environment.SANDBOX, System.getenv(SandboxFixtures.BT_MERCHANT_VAR),
				System.getenv(SandboxFixtures.BT_PUBLIC_VAR), System.getenv(SandboxFixtures.BT_PRIVATE_VAR));
	}

	private Transaction sale(String ref, String nonce) throws IntegrationException {
		return module.authorizeAndCapture(store, null, null, AMOUNT, payment("paymentToken", nonce, ref, AMOUNT),
				configuration, null);
	}

	/** Polls the order id search until Braintree returns the sale. */
	private GatewayPaymentStatus lookupWhenIndexed(PaymentAttempt attempt) throws Exception {
		long started = System.currentTimeMillis();
		GatewayPaymentStatus status = module.lookup(store, configuration, attempt);
		while (status.getOutcome() == Outcome.NOT_FOUND && System.currentTimeMillis() - started < SEARCH_WAIT_MS) {
			Thread.sleep(3_000);
			status = module.lookup(store, configuration, attempt);
		}
		return status;
	}

	@Test
	void saleIsFoundByOrderIdAndVoidedWhileUnsettled() throws Exception {
		String ref = newAttemptRef();
		Transaction sale = sale(ref, "fake-valid-nonce");
		String txId = sale.getTransactionDetails().get("TRANSACTIONID");
		PaymentAttempt attempt = attempt(ref, txId, AMOUNT);

		GatewayPaymentStatus status = lookupWhenIndexed(attempt);
		assertEquals(Outcome.SUCCEEDED, status.getOutcome());
		assertEquals(txId, status.getGatewayTransactionId());
		assertEquals(0, AMOUNT.compareTo(status.getAmount()));
		proof(GW, "sale lookup by orderId", txId + " -> " + status + " amount " + status.getAmount());

		Transaction voided = module.refund(false, store, sale, null, AMOUNT, configuration, null);
		assertEquals(TransactionType.REFUND, voided.getTransactionType());
		assertEquals("VOIDED", voided.getTransactionDetails().get("TRNAPPROVED"));
		assertEquals(Outcome.FAILED, module.lookup(store, configuration, attempt).getOutcome());
		proof(GW, "void unsettled", txId + " -> " + voided.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	void settledSaleIsRefunded() throws Exception {
		String ref = newAttemptRef();
		Transaction sale = sale(ref, "fake-valid-nonce");
		String txId = sale.getTransactionDetails().get("TRANSACTIONID");

		Result<com.braintreegateway.Transaction> settled = gateway.testing().settle(txId);
		assertTrue(settled.isSuccess(), "sandbox settle failed");

		Transaction refund = module.refund(true, store, sale, null, new BigDecimal("4.00"), configuration, null);
		assertEquals("REFUNDED", refund.getTransactionDetails().get("TRNAPPROVED"));
		proof(GW, "refund settled", txId + " " + settled.getTarget().getStatus() + " -> refund "
				+ refund.getTransactionDetails().get("TRANSACTIONID") + " 4.00 REFUNDED");
	}

	@Test
	void lateSaleIsCompensated() throws Exception {
		String ref = newAttemptRef();
		Transaction sale = sale(ref, "fake-valid-nonce");
		PaymentAttempt attempt = attempt(ref, sale.getTransactionDetails().get("TRANSACTIONID"), AMOUNT);
		GatewayPaymentStatus status = lookupWhenIndexed(attempt);

		Transaction comp = module.compensate(store, configuration, attempt, status);
		assertEquals(TransactionType.REFUND, comp.getTransactionType());
		proof(GW, "late sale compensation", status.getGatewayTransactionId() + " -> "
				+ comp.getTransactionDetails().get("TRNAPPROVED"));
	}

	@Test
	void declinedNonceLooksUpAsFailed() throws Exception {
		String ref = newAttemptRef();
		assertThrows(IntegrationException.class, () -> sale(ref, "fake-processor-declined-visa-nonce"));

		GatewayPaymentStatus status = lookupWhenIndexed(attempt(ref, null, AMOUNT));
		assertEquals(Outcome.FAILED, status.getOutcome());
		proof(GW, "declined nonce", ref + " -> " + status);
	}

	@Test
	void unknownReferenceIsNotFound() throws Exception {
		String ref = newAttemptRef();
		GatewayPaymentStatus status = module.lookup(store, configuration, attempt(ref, null, AMOUNT));
		assertEquals(Outcome.NOT_FOUND, status.getOutcome());
		proof(GW, "unknown reference", ref + " -> " + status);
	}
}
