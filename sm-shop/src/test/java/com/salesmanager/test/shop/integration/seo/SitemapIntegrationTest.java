package com.salesmanager.test.shop.integration.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.shop.model.catalog.category.PersistableCategory;
import com.salesmanager.shop.model.catalog.product.product.PersistableProduct;
import com.salesmanager.shop.model.catalog.product.product.ProductSpecification;
import com.salesmanager.shop.model.entity.Entity;
import com.salesmanager.shop.store.api.v1.seo.SitemapApi;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Runs the sitemap queries against the real schema (H2, hbm2ddl) with catalog
 * data created through the admin API. The controller is called directly; the
 * HTTP permit rule lives in the security configuration.
 */
class SitemapIntegrationTest extends ServicesTestSupport {

  @Autowired
  private SitemapApi sitemapApi;

  @Test
  void listsVisibleCategoriesAndAvailableProductsOfTheStore() throws Exception {
    PersistableCategory category = category("sitemapcat", "sitemap-tables");
    ResponseEntity<PersistableCategory> created = testRestTemplate.postForEntity(
        "/api/v1/private/category?store=" + Constants.DEFAULT_STORE,
        new HttpEntity<>(category, getHeader()), PersistableCategory.class);
    assertEquals(HttpStatus.CREATED, created.getStatusCode());

    createProduct("sitemap-oak", "sitemap-oak-bench", true);
    createProduct("sitemap-gone", "sitemap-retired-chair", false);

    ResponseEntity<String> response = sitemapApi.sitemap(Constants.DEFAULT_STORE);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    String xml = response.getBody();
    assertTrue(xml.contains("<loc>http://localhost:3000/</loc>"), xml);
    assertTrue(xml.contains("<loc>http://localhost:3000/category/sitemap-tables</loc>"), xml);
    assertTrue(xml.contains("<loc>http://localhost:3000/product/sitemap-oak-bench</loc>"), xml);
    assertFalse(xml.contains("sitemap-retired-chair"), xml);
  }

  @Test
  void unknownStoreIsNotFound() throws Exception {
    assertEquals(HttpStatus.NOT_FOUND, sitemapApi.sitemap("NO_SUCH_STORE").getStatusCode());
  }

  private void createProduct(String sku, String slug, boolean available) {
    PersistableProduct product = product(sku);
    product.getDescriptions().get(0).setFriendlyUrl(slug);
    ProductSpecification specifications = new ProductSpecification();
    specifications.setManufacturer(
        com.salesmanager.core.model.catalog.product.manufacturer.Manufacturer.DEFAULT_MANUFACTURER);
    product.setProductSpecifications(specifications);
    // The admin API maps "visible" onto Product.available, the flag the catalog filters on.
    product.setVisible(available);
    product.setAvailable(available);
    product.setPrice(BigDecimal.TEN);
    product.setSku(sku);
    product.setQuantity(10);

    ResponseEntity<Entity> response = testRestTemplate.postForEntity(
        "/api/v1/private/product?store=" + Constants.DEFAULT_STORE,
        new HttpEntity<>(product, getHeader()), Entity.class);
    assertEquals(HttpStatus.CREATED, response.getStatusCode());
  }
}
