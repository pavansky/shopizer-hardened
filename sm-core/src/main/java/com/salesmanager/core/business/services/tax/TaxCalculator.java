package com.salesmanager.core.business.services.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.salesmanager.core.model.tax.taxrate.TaxRate;

/**
 * Tax arithmetic with no persistence, so the rules can be tested on their own.
 *
 * Rounding policy: amounts are exact BigDecimal until a tax line is produced.
 * Each tax line (one rate applied to one tax class base) is rounded to 2
 * decimals with HALF_UP. Tax lines of the same rate code are then summed
 * without further rounding. Discount allocations are rounded the same way and
 * any rounding difference is settled on the largest lines, so the allocations add
 * up to the discount and no discounted line goes below zero.
 */
public final class TaxCalculator {

	private static final int SCALE = 2;
	private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

	private TaxCalculator() {
	}

	public static BigDecimal round(BigDecimal amount) {
		return amount.setScale(SCALE, ROUNDING);
	}

	/**
	 * Spreads an order level discount over the lines in proportion to their
	 * amounts and returns the discounted line amounts, in the same order.
	 * A discount larger than the lines is capped at their total.
	 *
	 * Each share is rounded HALF_UP to 2 decimals and never exceeds its line.
	 * The rounding difference (positive or negative) is then settled on the
	 * largest lines first, still keeping every share between zero and its
	 * line, so no discounted line is ever negative and the shares always add
	 * up to the discount.
	 */
	public static List<BigDecimal> allocateDiscount(List<BigDecimal> lineAmounts, BigDecimal discount) {
		List<BigDecimal> result = new ArrayList<BigDecimal>(lineAmounts);
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal amount : lineAmounts) {
			total = total.add(amount);
		}
		if (discount == null || discount.signum() <= 0 || total.signum() <= 0) {
			return result;
		}
		if (discount.compareTo(total) > 0) {
			discount = total;
		}

		int size = lineAmounts.size();
		BigDecimal[] shares = new BigDecimal[size];
		BigDecimal rest = discount;
		for (int i = 0; i < size; i++) {
			BigDecimal share = round(discount.multiply(lineAmounts.get(i)).divide(total, 10, ROUNDING));
			shares[i] = share.min(lineAmounts.get(i)).max(BigDecimal.ZERO);
			rest = rest.subtract(shares[i]);
		}

		//largest lines first, ties in cart order
		List<Integer> bySize = new ArrayList<Integer>();
		for (int i = 0; i < size; i++) {
			bySize.add(i);
		}
		bySize.sort((a, b) -> lineAmounts.get(b).compareTo(lineAmounts.get(a)));
		for (int i : bySize) {
			if (rest.signum() == 0) {
				break;
			}
			BigDecimal adjustment = rest.signum() > 0
					? rest.min(lineAmounts.get(i).subtract(shares[i]))
					: rest.max(shares[i].negate());
			shares[i] = shares[i].add(adjustment);
			rest = rest.subtract(adjustment);
		}

		for (int i = 0; i < size; i++) {
			result.set(i, lineAmounts.get(i).subtract(shares[i]));
		}
		return result;
	}

	/**
	 * Applies the rates of one tax class to its base, in the given order
	 * (the repository returns them by ascending priority).
	 * A non piggyback rate is applied to the base. A piggyback (compound) rate
	 * is applied to the base plus every tax already computed for this class.
	 *
	 * @return the rounded tax amount of each rate, in the same order as rates
	 */
	public static List<BigDecimal> applyRates(BigDecimal base, List<TaxRate> rates) {
		if (rates == null || rates.isEmpty()) {
			return Collections.emptyList();
		}
		List<BigDecimal> amounts = new ArrayList<BigDecimal>(rates.size());
		BigDecimal taxSoFar = BigDecimal.ZERO;
		for (TaxRate rate : rates) {
			BigDecimal taxable = rate.isPiggyback() ? base.add(taxSoFar) : base;
			BigDecimal amount = round(taxable.multiply(rate.getTaxRate()).movePointLeft(2));
			amounts.add(amount);
			taxSoFar = taxSoFar.add(amount);
		}
		return amounts;
	}
}
