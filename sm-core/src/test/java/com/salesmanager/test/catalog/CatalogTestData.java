package com.salesmanager.test.catalog;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import com.salesmanager.core.business.services.catalog.category.CategoryService;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.reference.country.CountryService;
import com.salesmanager.core.business.services.reference.currency.CurrencyService;
import com.salesmanager.core.business.services.reference.zone.ZoneService;
import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.category.CategoryDescription;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.description.ProductDescription;
import com.salesmanager.core.model.catalog.product.price.ProductPrice;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;

/** Builders shared by the catalog read-path tests. */
final class CatalogTestData {

	static final Date YESTERDAY = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);

	private CatalogTestData() {
	}

	static MerchantStore store(String code, Language en, MerchantStoreService merchantService,
			CountryService countryService, CurrencyService currencyService, ZoneService zoneService) throws Exception {
		MerchantStore existing = merchantService.getByCode(code);
		if (existing != null) {
			return existing;
		}
		List<Language> languages = new ArrayList<Language>();
		languages.add(en);
		MerchantStore store = new MerchantStore();
		store.setCountry(countryService.getByCode("CA"));
		store.setCurrency(currencyService.getByCode("CAD"));
		store.setDefaultLanguage(en);
		store.setInBusinessSince(YESTERDAY);
		store.setZone(zoneService.getByCode("QC"));
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
		return merchantService.getByCode(code);
	}

	static Category category(MerchantStore store, String code, Language en, CategoryService categoryService)
			throws Exception {
		Category category = new Category();
		category.setMerchantStore(store);
		category.setCode(code);
		CategoryDescription description = new CategoryDescription();
		description.setName(code);
		description.setSeUrl(code);
		description.setCategory(category);
		description.setLanguage(en);
		category.getDescriptions().add(description);
		categoryService.create(category);
		return category;
	}

	static Product product(MerchantStore store, ProductType type, String sku, int sortOrder, Language language,
			String slug) {
		Product product = new Product();
		product.setSku(sku);
		product.setType(type);
		product.setMerchantStore(store);
		product.setSortOrder(sortOrder);
		product.setDateAvailable(YESTERDAY);
		product.setAvailable(true);
		description(product, language, "Product " + sku, slug);

		ProductAvailability availability = new ProductAvailability();
		availability.setProductDateAvailable(YESTERDAY);
		availability.setProductQuantity(10);
		availability.setRegion("*");
		availability.setProduct(product);
		product.getAvailabilities().add(availability);

		ProductPrice price = new ProductPrice();
		price.setDefaultPrice(true);
		price.setProductPriceAmount(new BigDecimal("10.00"));
		price.setProductAvailability(availability);
		availability.getPrices().add(price);
		return product;
	}

	static void description(Product product, Language language, String name, String slug) {
		ProductDescription description = new ProductDescription();
		description.setName(name);
		description.setSeUrl(slug);
		description.setLanguage(language);
		description.setProduct(product);
		product.getDescriptions().add(description);
	}
}
