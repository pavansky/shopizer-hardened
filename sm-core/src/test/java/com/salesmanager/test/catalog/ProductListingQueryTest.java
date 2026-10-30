package com.salesmanager.test.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.persistence.EntityManagerFactory;

import org.hibernate.Hibernate;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.ProductCriteria;
import com.salesmanager.core.model.catalog.product.ProductList;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.test.common.AbstractSalesManagerCoreTestCase;

/**
 * The product listing pages in SQL (ids first, then the page of products) instead of
 * join-fetching every collection and paging in memory. The test configuration sets
 * hibernate.query.fail_on_pagination_over_collection_fetch=true, so a regression to
 * in-memory paging fails here.
 */
public class ProductListingQueryTest extends AbstractSalesManagerCoreTestCase {

	private static final int PRODUCTS = 25;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	public void pagesInSqlWithStableOrderAndCorrectCount() throws Exception {
		Language en = languageService.getByCode("en");
		MerchantStore store = CatalogTestData.store("LISTSTORE", en, merchantService, countryService,
				currencyService, zoneService);
		ProductType type = productTypeService.getProductType(ProductType.GENERAL_TYPE);
		Category chairs = CatalogTestData.category(store, "list-chairs", en, categoryService);
		Category tables = CatalogTestData.category(store, "list-tables", en, categoryService);

		// sort orders descend while ids ascend, and three products share sort order 5,
		// so the expected order only holds if the page is ordered by sortOrder then id
		List<Long> expected = new ArrayList<Long>();
		List<Product> created = new ArrayList<Product>();
		for (int i = 0; i < PRODUCTS; i++) {
			int sortOrder = i < 3 ? 5 : 100 - i;
			Product p = CatalogTestData.product(store, type, "LIST-" + i, sortOrder, en, "list-" + i);
			p.getCategories().add(chairs);
			if (i % 2 == 0) {
				p.getCategories().add(tables);
			}
			created.add(productService.saveProduct(p));
		}
		created.sort((a, b) -> a.getSortOrder().equals(b.getSortOrder()) ? a.getId().compareTo(b.getId())
				: a.getSortOrder().compareTo(b.getSortOrder()));
		for (Product p : created) {
			expected.add(p.getId());
		}

		// another store's products never count
		MerchantStore other = CatalogTestData.store("LISTOTHER", en, merchantService, countryService,
				currencyService, zoneService);
		productService.saveProduct(CatalogTestData.product(other, type, "LIST-OTHER", 0, en, "list-other"));

		// second page of 10, as the storefront asks for it
		Page<Product> page = productService.listByStore(store, en, shopCriteria(), 1, 10);
		Assert.assertEquals(PRODUCTS, page.getTotalElements());
		Assert.assertEquals(10, page.getContent().size());
		Assert.assertEquals(expected.subList(10, 20), ids(page.getContent()));

		// last page is partial
		Page<Product> last = productService.listByStore(store, en, shopCriteria(), 2, 10);
		Assert.assertEquals(expected.subList(20, 25), ids(last.getContent()));

		// the graph the list mappers read is loaded although the caller has no session
		Product first = page.getContent().get(0);
		Assert.assertTrue(Hibernate.isInitialized(first.getDescriptions()));
		Assert.assertEquals(1, first.getDescriptions().size());
		Assert.assertTrue(Hibernate.isInitialized(first.getAvailabilities()));
		Assert.assertEquals(1, first.getAvailabilities().iterator().next().getPrices().size());
		Assert.assertTrue(Hibernate.isInitialized(first.getCategories()));
		Assert.assertTrue(Hibernate.isInitialized(first.getAttributes()));
		Assert.assertTrue(Hibernate.isInitialized(first.getVariants()));
		Assert.assertTrue(Hibernate.isInitialized(first.getImages()));

		// category filter: count and page agree, and a product keeps all of its categories
		ProductCriteria byTables = shopCriteria();
		byTables.setCategoryIds(Collections.singletonList(tables.getId()));
		Page<Product> tablePage = productService.listByStore(store, en, byTables, 0, 100);
		Assert.assertEquals(13, tablePage.getTotalElements());
		Assert.assertEquals(13, tablePage.getContent().size());
		Assert.assertEquals(2, tablePage.getContent().get(0).getCategories().size());
	}

	@Test
	public void unavailableFilterStaysInsideTheStore() throws Exception {
		Language en = languageService.getByCode("en");
		MerchantStore store = CatalogTestData.store("AVAILSTORE", en, merchantService, countryService,
				currencyService, zoneService);
		MerchantStore other = CatalogTestData.store("AVAILOTHER", en, merchantService, countryService,
				currencyService, zoneService);
		ProductType type = productTypeService.getProductType(ProductType.GENERAL_TYPE);

		productService.saveProduct(CatalogTestData.product(store, type, "AV-ON", 1, en, "av-on"));
		Product off = CatalogTestData.product(store, type, "AV-OFF", 2, en, "av-off");
		off.setAvailable(false);
		productService.saveProduct(off);
		// available from tomorrow, so it matches the "p.dateAvailable>:dt" branch
		Product otherOff = CatalogTestData.product(other, type, "AV-OTHER", 1, en, "av-other");
		otherOff.setDateAvailable(new java.sql.Date(System.currentTimeMillis() + 24L * 3600 * 1000));
		productService.saveProduct(otherOff);

		// the count used to read "... and p.available=false or p.dateAvailable>:dt", so the
		// OR escaped the store filter and counted the other store's future product (2, not 1)
		ProductCriteria unavailable = adminCriteria();
		unavailable.setAvailable(false);
		ProductList list = productService.listByStore(store, en, unavailable);
		Assert.assertEquals(1, list.getTotalCount());
		Assert.assertEquals(Collections.singletonList(off.getId()), ids(list.getProducts()));

		ProductCriteria available = adminCriteria();
		available.setAvailable(true);
		Assert.assertEquals(1, productService.listByStore(store, en, available).getTotalCount());
	}

	@Test
	public void productWithoutAvailabilityIsNeitherCountedNorListed() throws Exception {
		Language en = languageService.getByCode("en");
		MerchantStore store = CatalogTestData.store("NOINVSTORE", en, merchantService, countryService,
				currencyService, zoneService);
		ProductType type = productTypeService.getProductType(ProductType.GENERAL_TYPE);

		Product stocked = productService.saveProduct(CatalogTestData.product(store, type, "INV-YES", 1, en, "inv-yes"));
		// a product must be created with an availability; the admin API can then delete it
		// (DELETE /api/v1/private/product/{id}/inventory/{inventoryId})
		Product bare = productService.saveProduct(CatalogTestData.product(store, type, "INV-NO", 2, en, "inv-no"));
		productAvailabilityService
				.delete(productService.findOne(bare.getId(), store).getAvailabilities().iterator().next());
		Assert.assertTrue(productService.findOne(bare.getId(), store).getAvailabilities().isEmpty());

		// the list mappers price every product, and a product with no availability row has
		// no price ("No applicable inventory"), so one such product broke the whole page
		Page<Product> page = productService.listByStore(store, en, shopCriteria(), 0, 10);
		Assert.assertEquals(1, page.getTotalElements());
		Assert.assertEquals(Collections.singletonList(stocked.getId()), ids(page.getContent()));

		ProductList admin = productService.listByStore(store, en, adminCriteria());
		Assert.assertEquals(1, admin.getTotalCount());
		Assert.assertEquals(Collections.singletonList(stocked.getId()), ids(admin.getProducts()));
	}

	@Test
	public void testConfigurationRejectsPagingOverACollectionFetch() {
		javax.persistence.EntityManager em = entityManagerFactory.createEntityManager();
		try {
			em.createQuery("select distinct p from Product p left join fetch p.descriptions")
					.setFirstResult(0).setMaxResults(5).getResultList();
			Assert.fail("paging over a collection fetch was accepted");
		} catch (RuntimeException e) {
			Throwable cause = e;
			boolean rejectedByConfiguration = false;
			while (cause != null) {
				if (cause instanceof org.hibernate.HibernateException && cause.getMessage() != null
						&& cause.getMessage().contains("firstResult/maxResults specified with collection fetch")) {
					rejectedByConfiguration = true;
				}
				cause = cause.getCause();
			}
			Assert.assertTrue("unexpected failure: " + e, rejectedByConfiguration);
		} finally {
			em.close();
		}
	}

	private static ProductCriteria shopCriteria() {
		ProductCriteria criteria = new ProductCriteria();
		criteria.setOrigin(ProductCriteria.ORIGIN_SHOP);
		criteria.setLanguage("en");
		return criteria;
	}

	private static ProductCriteria adminCriteria() {
		ProductCriteria criteria = new ProductCriteria();
		criteria.setOrigin(ProductCriteria.ORIGIN_ADMIN);
		criteria.setLanguage("en");
		criteria.setLegacyPagination(false);
		criteria.setStartPage(0);
		criteria.setPageSize(50);
		return criteria;
	}

	private static List<Long> ids(List<Product> products) {
		List<Long> ids = new ArrayList<Long>();
		for (Product p : products) {
			ids.add(p.getId());
		}
		return ids;
	}
}
