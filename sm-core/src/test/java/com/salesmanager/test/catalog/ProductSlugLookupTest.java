package com.salesmanager.test.catalog;

import java.util.Locale;

import org.hibernate.Hibernate;
import org.junit.Assert;
import org.junit.Test;

import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.test.common.AbstractSalesManagerCoreTestCase;

/**
 * A slug is looked up within one store, in the request language and then the store's
 * default language. Two stores may use the same slug, and an unknown slug returns null
 * (the API answers 404) instead of throwing.
 */
public class ProductSlugLookupTest extends AbstractSalesManagerCoreTestCase {

	private static final String SLUG = "shared-slug";

	@Test
	public void slugIsScopedByStoreAndLanguage() throws Exception {
		Language en = languageService.getByCode("en");
		Language fr = languageService.getByCode("fr");
		MerchantStore storeA = CatalogTestData.store("SLUGA", en, merchantService, countryService, currencyService,
				zoneService);
		MerchantStore storeB = CatalogTestData.store("SLUGB", en, merchantService, countryService, currencyService,
				zoneService);
		ProductType type = productTypeService.getProductType(ProductType.GENERAL_TYPE);
		Category category = CatalogTestData.category(storeA, "slug-category", en, categoryService);

		Product a = CatalogTestData.product(storeA, type, "SLUG-A", 0, en, SLUG);
		CatalogTestData.description(a, fr, "Produit A", "lien-partage");
		a.getCategories().add(category);
		a = productService.saveProduct(a);
		Product b = productService.saveProduct(CatalogTestData.product(storeB, type, "SLUG-B", 0, en, SLUG));

		Product foundA = productService.getBySeUrl(storeA, SLUG, new Locale("en"));
		Assert.assertNotNull(foundA);
		Assert.assertEquals(a.getId(), foundA.getId());

		Product foundB = productService.getBySeUrl(storeB, SLUG, new Locale("en"));
		Assert.assertNotNull(foundB);
		Assert.assertEquals(b.getId(), foundB.getId());
		Assert.assertEquals(storeB.getId(), foundB.getMerchantStore().getId());

		// a French request with the store's default-language (English) slug still finds the
		// product, so a storefront that switches language keeps its product URLs working
		Product fallback = productService.getBySeUrl(storeA, SLUG, new Locale("fr"));
		Assert.assertNotNull(fallback);
		Assert.assertEquals(a.getId(), fallback.getId());
		// a slug in a language that is neither the requested one nor the store default does not match
		Assert.assertNull(productService.getBySeUrl(storeA, "lien-partage", new Locale("es")));
		Product french = productService.getBySeUrl(storeA, "lien-partage", new Locale("fr"));
		Assert.assertNotNull(french);
		Assert.assertEquals(a.getId(), french.getId());
		// every description is loaded, so the page can be rendered in the request language
		Assert.assertEquals(2, french.getDescriptions().size());

		// store B cannot reach store A's French slug
		Assert.assertNull(productService.getBySeUrl(storeB, "lien-partage", new Locale("fr")));

		// unknown slug: null, which the API turns into 404 (it was a 500 "Index 0 out of bounds")
		Assert.assertNull(productService.getBySeUrl(storeA, "does-not-exist", new Locale("en")));

		// the page graph is complete without an open session
		Assert.assertTrue(Hibernate.isInitialized(foundA.getCategories()));
		Assert.assertEquals(1, foundA.getCategories().size());
		Assert.assertTrue(Hibernate.isInitialized(foundA.getAvailabilities()));
		Assert.assertTrue(Hibernate.isInitialized(foundA.getAttributes()));
		Assert.assertTrue(Hibernate.isInitialized(foundA.getVariants()));
	}

	@Test
	public void productLoaderIsStoreScoped() throws Exception {
		Language en = languageService.getByCode("en");
		MerchantStore storeA = CatalogTestData.store("LOADA", en, merchantService, countryService, currencyService,
				zoneService);
		MerchantStore storeB = CatalogTestData.store("LOADB", en, merchantService, countryService, currencyService,
				zoneService);
		ProductType type = productTypeService.getProductType(ProductType.GENERAL_TYPE);
		Product a = productService.saveProduct(CatalogTestData.product(storeA, type, "LOAD-A", 0, en, "load-a"));

		Product byId = productService.findOne(a.getId(), storeA);
		Assert.assertNotNull(byId);
		Assert.assertEquals(1, byId.getAvailabilities().iterator().next().getPrices().size());
		Assert.assertNull(productService.findOne(a.getId(), storeB));

		Product bySku = productService.getBySku("LOAD-A", storeA, en);
		Assert.assertEquals(a.getId(), bySku.getId());
		Assert.assertTrue(Hibernate.isInitialized(bySku.getDescriptions()));
	}
}
