package com.salesmanager.test.tax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Test;

import com.salesmanager.core.business.services.tax.TaxClassService;
import com.salesmanager.core.business.services.tax.TaxRateService;
import com.salesmanager.core.business.services.tax.TaxService;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.core.model.common.Delivery;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.reference.zone.Zone;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.tax.TaxBasisCalculation;
import com.salesmanager.core.model.tax.TaxConfiguration;
import com.salesmanager.core.model.tax.TaxItem;
import com.salesmanager.core.model.tax.taxclass.TaxClass;
import com.salesmanager.core.model.tax.taxrate.TaxRate;
import com.salesmanager.core.model.tax.taxrate.TaxRateDescription;
import com.salesmanager.test.common.AbstractSalesManagerCoreTestCase;

/**
 * Golden tax scenarios against the real repositories (H2). Every scenario
 * creates its own stores, so each store has its own DEFAULT tax class, as the
 * schema allows (TAX_CLASS is unique per MERCHANT_ID and code).
 */
public class TaxCalculationGoldenTest extends AbstractSalesManagerCoreTestCase {

	private static final AtomicInteger STORE_SEQUENCE = new AtomicInteger();
	private static final Date DATE = new Date(System.currentTimeMillis());

	@Inject
	private TaxService taxService;

	@Inject
	private TaxClassService taxClassService;

	@Inject
	private TaxRateService taxRateService;

	@Test
	public void twoTaxClassesInOneZoneAreTaxedSeparately() throws Exception {
		Language en = languageService.getByCode("en");
		Zone qc = zoneService.getByCode("QC");
		MerchantStore store = createStore(qc, TaxBasisCalculation.BILLINGADDRESS);
		TaxClass standard = taxClass(store, TaxClass.DEFAULT_TAX_CLASS);
		TaxClass reduced = taxClass(store, "REDUCED");
		rate(store, standard, qc, "STD", "10", en);
		rate(store, reduced, qc, "RED", "5", en);

		Map<String, BigDecimal> taxes = taxes(store, customer(qc, qc), en,
				line("100.00", 1, null), line("30.00", 2, reduced));

		Map<String, BigDecimal> expected = new TreeMap<String, BigDecimal>();
		expected.put("RED", new BigDecimal("3.00"));
		expected.put("STD", new BigDecimal("10.00"));
		assertEquals(expected, taxes);
	}

	@Test
	public void eachStoreUsesItsOwnDefaultTaxClass() throws Exception {
		Language en = languageService.getByCode("en");
		Zone qc = zoneService.getByCode("QC");
		MerchantStore first = createStore(qc, TaxBasisCalculation.BILLINGADDRESS);
		MerchantStore second = createStore(qc, TaxBasisCalculation.BILLINGADDRESS);
		rate(first, taxClass(first, TaxClass.DEFAULT_TAX_CLASS), qc, "VAT", "8", en);
		rate(second, taxClass(second, TaxClass.DEFAULT_TAX_CLASS), qc, "VAT", "13", en);

		assertEquals(single("VAT", "8.00"), taxes(first, customer(qc, qc), en, line("100.00", 1, null)));
		assertEquals(single("VAT", "13.00"), taxes(second, customer(qc, qc), en, line("100.00", 1, null)));
	}

	@Test
	public void taxBasisSelectsTheAddress() throws Exception {
		Language en = languageService.getByCode("en");
		Zone qc = zoneService.getByCode("QC");
		Zone on = zoneService.getByCode("ON");
		MerchantStore store = createStore(qc, TaxBasisCalculation.SHIPPINGADDRESS);
		TaxClass standard = taxClass(store, TaxClass.DEFAULT_TAX_CLASS);
		rate(store, standard, qc, "QST", "9.975", en);
		rate(store, standard, on, "HST", "13", en);

		// billed to Quebec, shipped to Ontario
		Customer customer = customer(qc, on);
		assertEquals(single("HST", "13.00"), taxes(store, customer, en, line("100.00", 1, null)));

		basis(store, TaxBasisCalculation.BILLINGADDRESS);
		assertEquals(single("QST", "9.98"), taxes(store, customer, en, line("100.00", 1, null)));

		basis(store, TaxBasisCalculation.STOREADDRESS);
		assertEquals(single("QST", "9.98"), taxes(store, customer(on, on), en, line("100.00", 1, null)));
	}

	@Test
	public void rateWithoutADescriptionInTheShopperLanguageStillApplies() throws Exception {
		Language en = languageService.getByCode("en");
		Language fr = languageService.getByCode("fr");
		assertNotNull(fr);
		Zone qc = zoneService.getByCode("QC");
		MerchantStore store = createStore(qc, TaxBasisCalculation.BILLINGADDRESS);
		rate(store, taxClass(store, TaxClass.DEFAULT_TAX_CLASS), qc, "STD", "10", en);

		assertEquals(single("STD", "10.00"), taxes(store, customer(qc, qc), fr, line("100.00", 1, null)));
	}

	@Test
	public void provinceRatesOfACountryWithoutZonesAreScopedByTaxClass() throws Exception {
		Language en = languageService.getByCode("en");
		Country gb = countryService.getByCode("GB");
		MerchantStore store = createStore(gb, null, TaxBasisCalculation.BILLINGADDRESS);
		TaxClass standard = taxClass(store, TaxClass.DEFAULT_TAX_CLASS);
		TaxClass reduced = taxClass(store, "REDUCED");
		provinceRate(store, standard, gb, "Wales", "VAT", "20", en);
		provinceRate(store, reduced, gb, "Wales", "VATR", "5", en);
		provinceRate(store, standard, gb, "Scotland", "OTHER", "7", en);

		Customer customer = new Customer();
		Billing billing = new Billing();
		billing.setCountry(gb);
		billing.setState("Wales");
		customer.setBilling(billing);

		Map<String, BigDecimal> expected = new TreeMap<String, BigDecimal>();
		expected.put("VAT", new BigDecimal("20.00"));
		expected.put("VATR", new BigDecimal("3.00"));
		assertEquals(expected, taxes(store, customer, en, line("100.00", 1, null), line("30.00", 2, reduced)));
	}

	// ---- helpers ----

	private Map<String, BigDecimal> taxes(MerchantStore store, Customer customer, Language language,
			ShoppingCartItem... lines) throws Exception {
		OrderSummary summary = new OrderSummary();
		summary.setProducts(new ArrayList<ShoppingCartItem>(Arrays.asList(lines)));
		List<TaxItem> items = taxService.calculateTax(summary, customer, store, language);
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

	private static ShoppingCartItem line(String price, int quantity, TaxClass taxClass) {
		Product product = new Product();
		product.setTaxClass(taxClass);
		ShoppingCartItem item = new ShoppingCartItem();
		item.setProduct(product);
		item.setItemPrice(new BigDecimal(price));
		item.setQuantity(quantity);
		return item;
	}

	private Customer customer(Zone billingZone, Zone deliveryZone) throws Exception {
		Country ca = countryService.getByCode("CA");
		Customer customer = new Customer();
		Billing billing = new Billing();
		billing.setCountry(ca);
		billing.setZone(billingZone);
		customer.setBilling(billing);
		Delivery delivery = new Delivery();
		delivery.setCountry(ca);
		delivery.setZone(deliveryZone);
		customer.setDelivery(delivery);
		return customer;
	}

	private TaxClass taxClass(MerchantStore store, String code) throws Exception {
		TaxClass taxClass = new TaxClass(code);
		taxClass.setTitle(code);
		taxClass.setMerchantStore(store);
		taxClassService.create(taxClass);
		return taxClassService.getByCode(code, store);
	}

	private void rate(MerchantStore store, TaxClass taxClass, Zone zone, String code, String percent,
			Language language) throws Exception {
		TaxRate rate = new TaxRate();
		rate.setCode(code);
		rate.setTaxRate(new BigDecimal(percent));
		rate.setTaxClass(taxClass);
		rate.setMerchantStore(store);
		rate.setCountry(countryService.getByCode("CA"));
		rate.setZone(zone);
		TaxRateDescription description = new TaxRateDescription();
		description.setName(code);
		description.setLanguage(language);
		description.setTaxRate(rate);
		rate.getDescriptions().add(description);
		taxRateService.create(rate);
	}

	private void provinceRate(MerchantStore store, TaxClass taxClass, Country country, String province, String code,
			String percent, Language language) throws Exception {
		TaxRate rate = new TaxRate();
		rate.setCode(code);
		rate.setTaxRate(new BigDecimal(percent));
		rate.setTaxClass(taxClass);
		rate.setMerchantStore(store);
		rate.setCountry(country);
		rate.setStateProvince(province);
		TaxRateDescription description = new TaxRateDescription();
		description.setName(code);
		description.setLanguage(language);
		description.setTaxRate(rate);
		rate.getDescriptions().add(description);
		taxRateService.create(rate);
	}

	private void basis(MerchantStore store, TaxBasisCalculation basis) throws Exception {
		TaxConfiguration configuration = new TaxConfiguration();
		configuration.setTaxBasisCalculation(basis);
		taxService.saveTaxConfiguration(configuration, store);
	}

	private MerchantStore createStore(Zone zone, TaxBasisCalculation basis) throws Exception {
		return createStore(countryService.getByCode("CA"), zone, basis);
	}

	private MerchantStore createStore(Country country, Zone zone, TaxBasisCalculation basis) throws Exception {
		String code = "TAXGOLD" + STORE_SEQUENCE.incrementAndGet();
		Language en = languageService.getByCode("en");
		Currency currency = currencyService.getByCode("CAD");
		List<Language> languages = new ArrayList<Language>();
		languages.add(en);

		MerchantStore store = new MerchantStore();
		store.setCountry(country);
		store.setZone(zone);
		store.setCurrency(currency);
		store.setDefaultLanguage(en);
		store.setInBusinessSince(DATE);
		store.setStorename(code);
		store.setStorephone("888-888-8888");
		store.setCode(code);
		store.setStorecity("My city");
		store.setStoreaddress("1234 Street address");
		store.setStorepostalcode("H2H-2H2");
		store.setStoreEmailAddress("contact@shopizer.com");
		store.setDomainName("localhost:8080");
		store.setStoreTemplate("december");
		store.setRetailer(true);
		store.setLanguages(languages);
		merchantService.create(store);

		MerchantStore saved = merchantService.getByCode(code);
		basis(saved, basis);
		return saved;
	}
}
