package com.salesmanager.core.business.modules.integration.payment.impl;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;

import org.junit.Test;

public class StripeAmountsTest {

	private static long usd(String amount) {
		return StripeAmounts.toMinorUnits(new BigDecimal(amount), "USD");
	}

	@Test
	public void twoDecimalCurrencies() {
		assertEquals(50L, usd("0.50"));
		assertEquals(1000L, usd("10"));
		assertEquals(1050L, usd("10.5"));
		assertEquals(1050L, usd("10.50"));
		assertEquals(123456L, usd("1234.56"));
		assertEquals(100000000L, usd("1000000.00"));
	}

	@Test
	public void roundsHalfEvenLikeTheDisplayedTotal() {
		assertEquals(1012L, usd("10.125"));
		assertEquals(1014L, usd("10.135"));
	}

	@Test
	public void currencyCodeIsCaseInsensitive() {
		assertEquals(123456L, StripeAmounts.toMinorUnits(new BigDecimal("1234.56"), "usd"));
	}

	@Test
	public void zeroDecimalCurrenciesAreSentAsIs() {
		assertEquals(100L, StripeAmounts.toMinorUnits(new BigDecimal("100"), "JPY"));
		assertEquals(100L, StripeAmounts.toMinorUnits(new BigDecimal("100.00"), "JPY"));
		assertEquals(100L, StripeAmounts.toMinorUnits(new BigDecimal("100"), "MGA"));
	}

	@Test
	public void iskAndUgxAreTwoDecimalWholeValues() {
		assertEquals(500L, StripeAmounts.toMinorUnits(new BigDecimal("5"), "ISK"));
		assertEquals(500L, StripeAmounts.toMinorUnits(new BigDecimal("5"), "UGX"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void subUnitZeroDecimalAmountIsRejected() {
		StripeAmounts.toMinorUnits(new BigDecimal("100.5"), "JPY");
	}

	@Test(expected = IllegalArgumentException.class)
	public void fractionalIskAmountIsRejected() {
		StripeAmounts.toMinorUnits(new BigDecimal("5.50"), "ISK");
	}

	@Test(expected = IllegalArgumentException.class)
	public void threeDecimalCurrencyIsRejected() {
		StripeAmounts.toMinorUnits(new BigDecimal("1.000"), "KWD");
	}

	@Test(expected = NullPointerException.class)
	public void nullAmountIsRejected() {
		StripeAmounts.toMinorUnits(null, "USD");
	}
}
