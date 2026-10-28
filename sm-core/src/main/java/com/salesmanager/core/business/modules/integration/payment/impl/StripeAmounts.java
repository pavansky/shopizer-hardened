package com.salesmanager.core.business.modules.integration.payment.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.Validate;

/**
 * Converts an amount to the integer minor-unit value Stripe expects.
 * Exponents follow Stripe's currency table (https://stripe.com/docs/currencies),
 * which differs from ISO 4217 for MGA, ISK and UGX.
 */
final class StripeAmounts {

	/** Stripe zero-decimal currencies: the amount is sent as-is. */
	private static final Set<String> ZERO_DECIMAL = new HashSet<String>(Arrays.asList(
			"BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
			"PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF"));

	/** Stripe wants these as two-decimal values whose decimal part is always 00. */
	private static final Set<String> TWO_DECIMAL_WHOLE = new HashSet<String>(Arrays.asList(
			"ISK", "UGX"));

	/** ISO 4217 three-decimal currencies; Stripe no longer documents them, reject rather than guess. */
	private static final Set<String> THREE_DECIMAL = new HashSet<String>(Arrays.asList(
			"BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"));

	private StripeAmounts() {}

	static long toMinorUnits(BigDecimal amount, String isoCode) {
		Validate.notNull(amount, "amount cannot be null");
		Validate.notBlank(isoCode, "currency code cannot be blank");
		String code = isoCode.trim().toUpperCase();

		if (THREE_DECIMAL.contains(code)) {
			throw new IllegalArgumentException("Currency " + code + " is not supported by the Stripe module");
		}

		if (TWO_DECIMAL_WHOLE.contains(code)) {
			BigDecimal whole = wholeUnits(amount, code);
			return whole.movePointRight(2).longValueExact();
		}

		if (ZERO_DECIMAL.contains(code)) {
			return wholeUnits(amount, code).longValueExact();
		}

		return amount.setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
	}

	private static BigDecimal wholeUnits(BigDecimal amount, String code) {
		try {
			return amount.setScale(0, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException e) {
			throw new IllegalArgumentException("Amount " + amount.toPlainString() + " has a fractional part, not allowed for " + code, e);
		}
	}
}
