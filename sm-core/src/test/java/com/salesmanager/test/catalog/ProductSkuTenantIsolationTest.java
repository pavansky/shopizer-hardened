package com.salesmanager.test.catalog;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.description.ProductDescription;
import com.salesmanager.core.model.catalog.product.price.ProductPrice;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.currency.Currency;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.reference.zone.Zone;

/**
 * The same SKU may exist in two stores (unique on MERCHANT_ID + SKU).
 * SKU lookups must only ever return the calling store's rows.
 */
public class ProductSkuTenantIsolationTest extends com.salesmanager.test.common.AbstractSalesManagerCoreTestCase {

	private static final Date date = new Date(System.currentTimeMillis());
	private static final String SKU = "SHARED1";

	@Test
	public void testSkuLookupsAreScopedToStore() throws Exception {

		Language en = languageService.getByCode("en");
		MerchantStore storeA = merchantService.getByCode(MerchantStore.DEFAULT_STORE);
		MerchantStore storeB = createStore("STORE_B", en);
		MerchantStore storeC = createStore("STORE_C", en);

		ProductType generalType = productTypeService.getProductType(ProductType.GENERAL_TYPE);

		Product productA = productService.saveProduct(buildProduct(storeA, generalType, en, "10.00"));
		Product productB = productService.saveProduct(buildProduct(storeB, generalType, en, "20.00"));

		// getBySku returns the calling store's product
		Product found = productService.getBySku(SKU, storeB, en);
		Assert.assertNotNull(found);
		Assert.assertEquals(storeB.getId(), found.getMerchantStore().getId());

		// exists is store scoped
		Assert.assertTrue(productService.exists(SKU, storeA));
		Assert.assertTrue(productService.exists(SKU, storeB));
		Assert.assertFalse(productService.exists(SKU, storeC));

		// price list only contains store B rows
		List<ProductPrice> pricesB = productPriceService.findByProductSku(SKU, storeB);
		Assert.assertEquals(1, pricesB.size());
		Assert.assertEquals(storeB.getId(),
				pricesB.get(0).getProductAvailability().getProduct().getMerchantStore().getId());

		// a price id belonging to store A is not visible from store B
		Long priceAId = productPriceService.findByProductSku(SKU, storeA).get(0).getId();
		Assert.assertNull(productPriceService.findById(priceAId, SKU, storeB));

		// with two prices on the product, lookup by id returns exactly that price
		ProductAvailability availabilityB = productB.getAvailabilities().iterator().next();
		ProductPrice second = new ProductPrice();
		second.setDefaultPrice(false);
		second.setCode("special");
		second.setProductPriceAmount(new BigDecimal("15.00"));
		second.setProductAvailability(availabilityB);
		productPriceService.saveOrUpdate(second);

		Long priceBId = pricesB.get(0).getId();
		ProductPrice byId = productPriceService.findById(priceBId, SKU, storeB);
		Assert.assertNotNull(byId);
		Assert.assertEquals(priceBId, byId.getId());

		Assert.assertNotNull(productA.getId());
	}

	private MerchantStore createStore(String code, Language en) throws Exception {
		Country ca = countryService.getByCode("CA");
		Currency currency = currencyService.getByCode("CAD");
		Zone qc = zoneService.getByCode("QC");

		List<Language> supportedLanguages = new ArrayList<Language>();
		supportedLanguages.add(en);

		MerchantStore store = new MerchantStore();
		store.setCountry(ca);
		store.setCurrency(currency);
		store.setDefaultLanguage(en);
		store.setInBusinessSince(date);
		store.setZone(qc);
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
		store.setLanguages(supportedLanguages);

		merchantService.create(store);
		return merchantService.getByCode(code);
	}

	private Product buildProduct(MerchantStore store, ProductType type, Language en, String amount) {
		Product product = new Product();
		product.setSku(SKU);
		product.setType(type);
		product.setMerchantStore(store);

		ProductDescription description = new ProductDescription();
		description.setName("Shared " + store.getCode());
		description.setLanguage(en);
		description.setProduct(product);
		product.getDescriptions().add(description);

		ProductAvailability availability = new ProductAvailability();
		availability.setProductDateAvailable(date);
		availability.setProductQuantity(10);
		availability.setRegion("*");
		availability.setProduct(product);
		product.getAvailabilities().add(availability);

		ProductPrice price = new ProductPrice();
		price.setDefaultPrice(true);
		price.setProductPriceAmount(new BigDecimal(amount));
		price.setProductAvailability(availability);
		availability.getPrices().add(price);

		return product;
	}
}
