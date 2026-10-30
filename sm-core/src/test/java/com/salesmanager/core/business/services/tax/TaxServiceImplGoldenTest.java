package com.salesmanager.core.business.services.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.business.services.shipping.ShippingService;
import com.salesmanager.core.business.services.system.MerchantConfigurationService;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.core.model.common.Delivery;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.reference.zone.Zone;
import com.salesmanager.core.model.shipping.ShippingConfiguration;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.system.MerchantConfiguration;
import com.salesmanager.core.model.tax.TaxItem;
import com.salesmanager.core.model.tax.taxclass.TaxClass;
import com.salesmanager.core.model.tax.taxrate.TaxRate;
import com.salesmanager.core.model.tax.taxrate.TaxRateDescription;

/**
 * Golden scenarios for TaxServiceImpl. The rate service is replaced by a fake
 * that returns the rates of the requested zone and tax class, as the
 * repository does; the store scoping of the queries themselves is covered by
 * the database backed TaxCalculationGoldenTest.
 */
class TaxServiceImplGoldenTest {

	private final TaxServiceImpl service = new TaxServiceImpl();
	private final MerchantConfigurationService configurationService = mock(MerchantConfigurationService.class);
	private final TaxRateService taxRateService = mock(TaxRateService.class);
	private final TaxClassService taxClassService = mock(TaxClassService.class);
	private final ShippingService shippingService = mock(ShippingService.class);

	private final Country canada = new Country("CA");
	private final Zone quebec = zone(10L, "QC");
	private final Zone ontario = zone(11L, "ON");
	private final MerchantStore store = new MerchantStore();
	private final Language english = new Language("en");
	private final TaxClass standard = taxClass(1L, TaxClass.DEFAULT_TAX_CLASS);
	private final TaxClass reduced = taxClass(2L, "REDUCED");
	private final List<TaxRate> rates = new ArrayList<TaxRate>();
	private final ShippingConfiguration shippingConfiguration = new ShippingConfiguration();

	@BeforeEach
	void setUp() throws Exception {
		inject("merchantConfigurationService", configurationService);
		inject("taxRateService", taxRateService);
		inject("taxClassService", taxClassService);
		inject("shippingService", shippingService);

		canada.setId(1);
		store.setId(1);
		store.setCountry(canada);
		store.setZone(quebec);

		when(taxClassService.getByCode(TaxClass.DEFAULT_TAX_CLASS, store)).thenReturn(standard);
		when(shippingService.getShippingConfiguration(store)).thenReturn(shippingConfiguration);
		when(taxRateService.listByCountryZoneAndTaxClass(any(), any(), any(), eq(store), any())).thenAnswer(inv -> {
			Zone zone = inv.getArgument(1);
			TaxClass taxClass = inv.getArgument(2);
			return rates.stream()
					.filter(r -> r.getZone().getId().equals(zone.getId()))
					.filter(r -> r.getTaxClass().getId().equals(taxClass.getId()))
					.collect(Collectors.toList());
		});
		basis("BILLINGADDRESS");
	}

	@Test
	void shippingAddressBasisTaxesAtTheDeliveryZone() throws Exception {
		rates.add(rate("QST", "9.975", quebec, standard, false));
		rates.add(rate("HST", "13", ontario, standard, false));
		basis("SHIPPINGADDRESS");

		Map<String, BigDecimal> taxes = taxes(summary(line("100.00", 1, null)), customer(quebec, ontario));

		assertEquals(single("HST", "13.00"), taxes);
	}

	@Test
	void billingAddressBasisTaxesAtTheBillingZone() throws Exception {
		rates.add(rate("QST", "9.975", quebec, standard, false));
		rates.add(rate("HST", "13", ontario, standard, false));
		basis("BILLINGADDRESS");

		Map<String, BigDecimal> taxes = taxes(summary(line("100.00", 1, null)), customer(quebec, ontario));

		// 9.975% of 100.00 is 9.975, HALF_UP gives 9.98 (double arithmetic gave 9.97)
		assertEquals(single("QST", "9.98"), taxes);
	}

	@Test
	void storeAddressBasisTaxesAtTheStoreZone() throws Exception {
		rates.add(rate("QST", "9.975", quebec, standard, false));
		rates.add(rate("HST", "13", ontario, standard, false));
		basis("STOREADDRESS");

		Map<String, BigDecimal> taxes = taxes(summary(line("100.00", 1, null)), customer(ontario, ontario));

		assertEquals(single("QST", "9.98"), taxes);
	}

	@Test
	void eachTaxClassIsTaxedOnItsOwnBaseAtItsOwnRates() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		rates.add(rate("RED", "5", quebec, reduced, false));

		Map<String, BigDecimal> taxes = taxes(
				summary(line("100.00", 1, null), line("30.00", 2, reduced)), customer(quebec, quebec));

		Map<String, BigDecimal> expected = new TreeMap<String, BigDecimal>();
		expected.put("RED", new BigDecimal("3.00"));
		expected.put("STD", new BigDecimal("10.00"));
		assertEquals(expected, taxes);
	}

	@Test
	void shippingIsNotTaxedWhenTheStoreDoesNotTaxShipping() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		shippingConfiguration.setTaxOnShipping(false);

		OrderSummary summary = summary(line("100.00", 1, null));
		summary.setShippingSummary(shipping("20.00", false));

		assertEquals(single("STD", "10.00"), taxes(summary, customer(quebec, quebec)));
	}

	@Test
	void shippingIsTaxedAtTheDefaultClassWhenTheStoreTaxesShipping() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		rates.add(rate("RED", "5", quebec, reduced, false));
		shippingConfiguration.setTaxOnShipping(true);

		OrderSummary summary = summary(line("100.00", 1, null), line("30.00", 2, reduced));
		summary.setShippingSummary(shipping("20.00", false));

		Map<String, BigDecimal> expected = new TreeMap<String, BigDecimal>();
		expected.put("RED", new BigDecimal("3.00"));
		expected.put("STD", new BigDecimal("12.00"));
		assertEquals(expected, taxes(summary, customer(quebec, quebec)));
	}

	@Test
	void freeShippingIsNotTaxed() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		shippingConfiguration.setTaxOnShipping(true);

		OrderSummary summary = summary(line("100.00", 1, null));
		summary.setShippingSummary(shipping("20.00", true));

		assertEquals(single("STD", "10.00"), taxes(summary, customer(quebec, quebec)));
	}

	@Test
	void piggybackRateCompoundsWithoutChangingTheBaseOfLaterRates() throws Exception {
		TaxRate gst = rate("GST", "5", quebec, standard, false);
		TaxRate pst = rate("PST", "10", quebec, standard, true);
		TaxRate eco = rate("ECO", "1", quebec, standard, false);
		gst.setTaxPriority(0);
		pst.setTaxPriority(1);
		eco.setTaxPriority(2);
		rates.addAll(Arrays.asList(gst, pst, eco));

		Map<String, BigDecimal> taxes = taxes(summary(line("100.00", 1, null)), customer(quebec, quebec));

		Map<String, BigDecimal> expected = new TreeMap<String, BigDecimal>();
		expected.put("ECO", new BigDecimal("1.00"));   // 1% of 100.00
		expected.put("GST", new BigDecimal("5.00"));   // 5% of 100.00
		expected.put("PST", new BigDecimal("10.50"));  // 10% of 105.00
		assertEquals(expected, taxes);
	}

	@Test
	void orderDiscountIsAllocatedToTheTaxClassesBeforeTax() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		rates.add(rate("RED", "5", quebec, reduced, false));

		// 20.00 off 160.00: 12.50 off the standard line, 7.50 off the reduced lines
		List<TaxItem> items = service.calculateTax(
				summary(line("100.00", 1, null), line("30.00", 2, reduced)), customer(quebec, quebec), store, english,
				new BigDecimal("20.00"));

		Map<String, BigDecimal> expected = new TreeMap<String, BigDecimal>();
		expected.put("RED", new BigDecimal("2.63"));   // 52.50 x 5% = 2.625
		expected.put("STD", new BigDecimal("8.75"));   // 87.50 x 10%
		assertEquals(expected, byCode(items));
	}

	@Test
	void handlingIsTaxedWithShippingWhenTheStoreChargesIt() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		shippingConfiguration.setTaxOnShipping(true);
		shippingConfiguration.setHandlingFees(new BigDecimal("5.00"));

		OrderSummary summary = summary(line("100.00", 1, null));
		ShippingSummary shipping = shipping("20.00", true);
		shipping.setHandling(new BigDecimal("5.00"));
		summary.setShippingSummary(shipping);

		// free shipping, but the 5.00 handling is still charged and taxed
		assertEquals(single("STD", "10.50"), taxes(summary, customer(quebec, quebec)));
	}

	@Test
	void storeWithoutADefaultClassAndSeveralClassesOnlyTaxesClassifiedLines() throws Exception {
		rates.add(rate("RED", "5", quebec, reduced, false));
		when(taxClassService.getByCode(TaxClass.DEFAULT_TAX_CLASS, store)).thenReturn(null);
		when(taxClassService.listByStore(store)).thenReturn(Arrays.asList(reduced, taxClass(3L, "LUXURY")));

		Map<String, BigDecimal> taxes = taxes(
				summary(line("100.00", 1, null), line("30.00", 2, reduced)), customer(quebec, quebec));

		assertEquals(single("RED", "3.00"), taxes);
	}

	@Test
	void storeWithoutADefaultClassAndOneClassDoesNotTaxUnclassifiedLines() throws Exception {
		// a store created through the API has no DEFAULT class; its only class must not stand in for it
		rates.add(rate("VAT", "5", quebec, reduced, false));
		when(taxClassService.getByCode(TaxClass.DEFAULT_TAX_CLASS, store)).thenReturn(null);
		when(taxClassService.listByStore(store)).thenReturn(Arrays.asList(reduced));

		Map<String, BigDecimal> taxes = taxes(
				summary(line("100.00", 1, null), line("30.00", 2, reduced)), customer(quebec, quebec));

		assertEquals(single("VAT", "3.00"), taxes);
	}

	@Test
	void taxLinesAreListedByPriorityNotByCode() throws Exception {
		TaxRate base = rate("ZZ_BASE", "5", quebec, standard, false);
		TaxRate compound = rate("AA_COMPOUND", "10", quebec, standard, true);
		base.setTaxPriority(0);
		compound.setTaxPriority(1);
		rates.addAll(Arrays.asList(base, compound));

		List<TaxItem> items = service.calculateTax(summary(line("100.00", 1, null)), customer(quebec, quebec), store, english);

		assertEquals(Arrays.asList("ZZ_BASE", "AA_COMPOUND"),
				items.stream().map(i -> i.getTaxRate().getCode()).collect(Collectors.toList()));
	}

	@Test
	void noCustomerMeansNoTax() throws Exception {
		rates.add(rate("STD", "10", quebec, standard, false));
		assertEquals(null, service.calculateTax(summary(line("100.00", 1, null)), null, store, english));
	}

	@AfterEach
	void neverUsesTheUnscopedTaxClassLookup() throws Exception {
		verify(taxClassService, never()).getByCode(anyString());
	}

	// ---- helpers ----

	private Map<String, BigDecimal> taxes(OrderSummary summary, Customer customer) throws Exception {
		return byCode(service.calculateTax(summary, customer, store, english));
	}

	private static Map<String, BigDecimal> byCode(List<TaxItem> items) {
		Map<String, BigDecimal> byCode = new TreeMap<String, BigDecimal>();
		if (items != null) {
			for (TaxItem item : items) {
				byCode.merge(item.getTaxRate().getCode(), item.getItemPrice(), BigDecimal::add);
			}
		}
		return byCode;
	}

	private static Map<String, BigDecimal> single(String code, String amount) {
		Map<String, BigDecimal> map = new TreeMap<String, BigDecimal>();
		map.put(code, new BigDecimal(amount));
		return map;
	}

	private void basis(String basis) throws Exception {
		MerchantConfiguration configuration = new MerchantConfiguration();
		configuration.setValue("{\"taxBasisCalculation\":\"" + basis + "\"}");
		when(configurationService.getMerchantConfiguration("TAX_CONFIG", store)).thenReturn(configuration);
	}

	private Customer customer(Zone billingZone, Zone deliveryZone) {
		Customer customer = new Customer();
		Billing billing = new Billing();
		billing.setCountry(canada);
		billing.setZone(billingZone);
		customer.setBilling(billing);
		Delivery delivery = new Delivery();
		delivery.setCountry(canada);
		delivery.setZone(deliveryZone);
		customer.setDelivery(delivery);
		return customer;
	}

	private static OrderSummary summary(ShoppingCartItem... items) {
		OrderSummary summary = new OrderSummary();
		summary.setProducts(new ArrayList<ShoppingCartItem>(Arrays.asList(items)));
		return summary;
	}

	private static ShoppingCartItem line(String price, int quantity, TaxClass taxClass) {
		Product product = new Product();
		product.setTaxClass(taxClass);
		ShoppingCartItem item = new ShoppingCartItem();
		item.setProduct(product);
		item.setItemPrice(new BigDecimal(price));
		item.setQuantity(quantity);
		return item;
	}

	private static ShippingSummary shipping(String amount, boolean free) {
		ShippingSummary shipping = new ShippingSummary();
		shipping.setShipping(new BigDecimal(amount));
		shipping.setFreeShipping(free);
		return shipping;
	}

	private TaxRate rate(String code, String percent, Zone zone, TaxClass taxClass, boolean piggyback) {
		TaxRate rate = new TaxRate();
		rate.setCode(code);
		rate.setTaxRate(new BigDecimal(percent));
		rate.setZone(zone);
		rate.setCountry(canada);
		rate.setTaxClass(taxClass);
		rate.setPiggyback(piggyback);
		rate.setMerchantStore(store);
		TaxRateDescription description = new TaxRateDescription();
		description.setName(code);
		description.setLanguage(english);
		rate.getDescriptions().add(description);
		return rate;
	}

	private static Zone zone(Long id, String code) {
		Zone zone = new Zone();
		zone.setId(id);
		zone.setCode(code);
		zone.setName(code);
		return zone;
	}

	private static TaxClass taxClass(Long id, String code) {
		TaxClass taxClass = new TaxClass(code);
		taxClass.setId(id);
		return taxClass;
	}

	private void inject(String name, Object value) throws Exception {
		Field field = TaxServiceImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(service, value);
	}
}
