package com.salesmanager.core.modules.integration.payment.model;

import java.math.BigDecimal;

/**
 * The gateway's answer about one payment attempt.
 */
public final class GatewayPaymentStatus {

	public enum Outcome {
		/** Money was taken (captured, or authorised and held). */
		SUCCEEDED,
		/** The gateway has the payment and it did not, or will not, take money. */
		FAILED,
		/** The gateway has no payment for this attempt. */
		NOT_FOUND,
		/** The gateway has the payment but it is not final yet, or the module cannot tell. */
		PENDING
	}

	private final Outcome outcome;
	private final String gatewayTransactionId;
	private final BigDecimal amount;
	private final String detail;
	private final boolean captured;

	private GatewayPaymentStatus(Outcome outcome, String gatewayTransactionId, BigDecimal amount, String detail,
			boolean captured) {
		this.outcome = outcome;
		this.gatewayTransactionId = gatewayTransactionId;
		this.amount = amount;
		this.detail = detail;
		this.captured = captured;
	}

	private GatewayPaymentStatus(Outcome outcome, String gatewayTransactionId, BigDecimal amount, String detail) {
		this(outcome, gatewayTransactionId, amount, detail, false);
	}

	/** Money captured (or submitted for settlement). */
	public static GatewayPaymentStatus succeeded(String gatewayTransactionId, BigDecimal amount, String detail) {
		return new GatewayPaymentStatus(Outcome.SUCCEEDED, gatewayTransactionId, amount, detail, true);
	}

	/** Money authorised and held, not captured yet. */
	public static GatewayPaymentStatus authorized(String gatewayTransactionId, BigDecimal amount, String detail) {
		return new GatewayPaymentStatus(Outcome.SUCCEEDED, gatewayTransactionId, amount, detail, false);
	}

	public static GatewayPaymentStatus failed(String gatewayTransactionId, String detail) {
		return new GatewayPaymentStatus(Outcome.FAILED, gatewayTransactionId, null, detail);
	}

	public static GatewayPaymentStatus notFound() {
		return new GatewayPaymentStatus(Outcome.NOT_FOUND, null, null, null);
	}

	public static GatewayPaymentStatus pending(String gatewayTransactionId, String detail) {
		return new GatewayPaymentStatus(Outcome.PENDING, gatewayTransactionId, null, detail);
	}

	public Outcome getOutcome() { return outcome; }
	public String getGatewayTransactionId() { return gatewayTransactionId; }
	public BigDecimal getAmount() { return amount; }
	/** Raw gateway status, for logs and history. */
	public String getDetail() { return detail; }
	/** For SUCCEEDED: true when captured, false when only authorised. */
	public boolean isCaptured() { return captured; }

	@Override
	public String toString() {
		return outcome + (detail == null ? "" : " (" + detail + ")");
	}
}
