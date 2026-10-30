package com.salesmanager.core.business.services.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.salesmanager.core.model.tax.taxrate.TaxRate;

class TaxCalculatorTest {

	@Test
	void discountAllocationAddsUpToTheDiscount() {
		List<BigDecimal> result = TaxCalculator.allocateDiscount(amounts("10.00", "10.00", "10.00"), new BigDecimal("10.00"));

		// 3.33 + 3.33 off two lines, the remaining 3.34 off the first largest line
		assertEquals(amounts("6.66", "6.67", "6.67"), result);
	}

	@Test
	void discountLargerThanTheLinesIsCapped() {
		assertEquals(amounts("0.00", "0.00"),
				TaxCalculator.allocateDiscount(amounts("20.00", "10.00"), new BigDecimal("50.00")));
	}

	@Test
	void noDiscountLeavesTheLinesUnchanged() {
		assertEquals(amounts("20.00", "10.00"), TaxCalculator.allocateDiscount(amounts("20.00", "10.00"), null));
		assertEquals(amounts("20.00", "10.00"), TaxCalculator.allocateDiscount(amounts("20.00", "10.00"), BigDecimal.ZERO));
	}

	@Test
	void eachRateIsRoundedHalfUpOnItsOwn() {
		// 9.975% of 100.00 = 9.975 -> 9.98, 5% of 100.00 = 5.00
		assertEquals(amounts("5.00", "9.98"),
				TaxCalculator.applyRates(new BigDecimal("100.00"), Arrays.asList(rate("5", false), rate("9.975", false))));
	}

	@Test
	void piggybackRateIsAppliedOnTheBasePlusEarlierTaxes() {
		// Quebec before 2013: QST 7.5% on (price + 5% GST)
		assertEquals(amounts("5.00", "7.88"),
				TaxCalculator.applyRates(new BigDecimal("100.00"), Arrays.asList(rate("5", false), rate("7.5", true))));
	}

	private static TaxRate rate(String percent, boolean piggyback) {
		TaxRate rate = new TaxRate();
		rate.setTaxRate(new BigDecimal(percent));
		rate.setPiggyback(piggyback);
		return rate;
	}

	private static List<BigDecimal> amounts(String... values) {
		BigDecimal[] result = new BigDecimal[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = new BigDecimal(values[i]);
		}
		return Arrays.asList(result);
	}
}
