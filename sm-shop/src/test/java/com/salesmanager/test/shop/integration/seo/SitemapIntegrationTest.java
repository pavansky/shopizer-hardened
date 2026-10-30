package com.salesmanager.test.shop.integration.seo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.shop.model.catalog.category.PersistableCategory;
import com.salesmanager.shop.model.catalog.product.product.PersistableProduct;
import com.salesmanager.shop.model.catalog.product.product.ProductSpecification;
import com.salesmanager.shop.model.entity.Entity;
import com.salesmanager.shop.model.references.PersistableAddress;
import com.salesmanager.shop.model.store.PersistableMerchantStore;
import com.salesmanager.shop.store.api.v1.seo.SitemapApi;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Runs the sitemap queries against the real schema (H2, hbm2ddl) with catalog
 * data created through the admin API. The controller is called directly because
 * the HTTP permit rule lives in the security configuration; see
 * {@link #isPublicOverHttp()}.
 */
class SitemapIntegrationTest extends ServicesTestSupport {

  private static final String OTHER_STORE = "sitemapb";

  @Autowired
  private SitemapApi sitemapApi;

  @BeforeEach
  void disableTheResponseCache() {
    // Each test changes the catalog and reads the sitemap right after.
    ReflectionTestUtils.setField(sitemapApi, "cacheSeconds", 0L);
    ((java.util.Map<?, ?>) ReflectionTestUtils.getField(sitemapApi, "cache")).clear();
  }

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
  void leavesOutHiddenCategoriesTheirChildrenAndProductsNotYetAvailable() throws Exception {
    PersistableCategory hidden = category("sitemaphidden", "sitemap-hidden-room");
    hidden.setVisible(false);
    PersistableCategory hiddenParent = createCategory(hidden, Constants.DEFAULT_STORE);

    PersistableCategory child = category("sitemapchild", "sitemap-under-hidden");
    com.salesmanager.shop.model.catalog.category.Category parent =
        new com.salesmanager.shop.model.catalog.category.Category();
    parent.setId(hiddenParent.getId());
    parent.setCode(hiddenParent.getCode());
    child.setParent(parent);
    createCategory(child, Constants.DEFAULT_STORE);

    PersistableProduct later = product("sitemap-later");
    later.getDescriptions().get(0).setFriendlyUrl("sitemap-next-season");
    later.setDateAvailable(LocalDate.now().plusDays(2).toString());
    postProduct(later, "sitemap-later", true, Constants.DEFAULT_STORE);

    String xml = sitemapApi.sitemap(Constants.DEFAULT_STORE).getBody();

    assertFalse(xml.contains("sitemap-hidden-room"), xml);
    assertFalse(xml.contains("sitemap-under-hidden"), xml);
    assertFalse(xml.contains("sitemap-next-season"), xml);
  }

  @Test
  void listsOnlyTheRequestedStoresCatalog() throws Exception {
    createStore(OTHER_STORE);
    createCategory(category("sitemapbcat", "sitemap-store-b-room"), OTHER_STORE);
    PersistableProduct otherProduct = product("sitemap-b-lamp");
    otherProduct.getDescriptions().get(0).setFriendlyUrl("sitemap-store-b-lamp");
    postProduct(otherProduct, "sitemap-b-lamp", true, OTHER_STORE);

    createCategory(category("sitemapacat", "sitemap-store-a-room"), Constants.DEFAULT_STORE);
    createProduct("sitemap-a-desk", "sitemap-store-a-desk", true);

    String defaultXml = sitemapApi.sitemap(Constants.DEFAULT_STORE).getBody();
    String otherXml = sitemapApi.sitemap(OTHER_STORE).getBody();

    assertTrue(defaultXml.contains("/category/sitemap-store-a-room"), defaultXml);
    assertTrue(defaultXml.contains("/product/sitemap-store-a-desk"), defaultXml);
    assertFalse(defaultXml.contains("sitemap-store-b-"), defaultXml);

    assertTrue(otherXml.contains("/category/sitemap-store-b-room"), otherXml);
    assertTrue(otherXml.contains("/product/sitemap-store-b-lamp"), otherXml);
    assertFalse(otherXml.contains("sitemap-store-a-"), otherXml);
  }

  /**
   * Enable once the default security chain permits GET /api/v1/sitemap.xml, so the
   * rule cannot be dropped without a failing test.
   */
  @Test
  @Disabled("Needs the GET /api/v1/sitemap.xml permit rule in MultipleEntryPointsSecurityConfig")
  void isPublicOverHttp() {
    ResponseEntity<String> response = testRestTemplate.exchange(
        "/api/v1/sitemap.xml?store=" + Constants.DEFAULT_STORE, HttpMethod.GET,
        HttpEntity.EMPTY, String.class);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getHeaders().getContentType().isCompatibleWith(
        org.springframework.http.MediaType.APPLICATION_XML), response.toString());
    assertTrue(response.getBody().contains("<urlset"), response.getBody());
  }

  @Test
  void unknownStoreIsNotFound() throws Exception {
    assertEquals(HttpStatus.NOT_FOUND, sitemapApi.sitemap("NO_SUCH_STORE").getStatusCode());
  }

  private PersistableCategory createCategory(PersistableCategory category, String store) {
    ResponseEntity<PersistableCategory> created = testRestTemplate.postForEntity(
        "/api/v1/private/category?store=" + store,
        new HttpEntity<>(category, getHeader()), PersistableCategory.class);
    assertEquals(HttpStatus.CREATED, created.getStatusCode(), String.valueOf(created));
    return created.getBody();
  }

  private void createStore(String code) {
    PersistableAddress address = new PersistableAddress();
    address.setAddress("1 Sitemap Street");
    address.setPostalCode("12345");
    address.setCountry("CA");
    address.setCity("Toronto");
    address.setStateProvince("ON");

    PersistableMerchantStore store = new PersistableMerchantStore();
    store.setCode(code);
    store.setName(code);
    store.setCurrency("CAD");
    store.setDefaultLanguage("en");
    store.setSupportedLanguages(Arrays.asList("en"));
    store.setEmail("sitemap@example.com");
    store.setPhone("444-555-6666");
    store.setAddress(address);

    ResponseEntity<Void> response = testRestTemplate.exchange("/api/v1/private/store/", HttpMethod.POST,
        new HttpEntity<>(store, getHeader()), Void.class);
    assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response));

    // Products reference the store's DEFAULT manufacturer.
    ResponseEntity<String> manufacturer = testRestTemplate.postForEntity(
        "/api/v1/private/manufacturer?store=" + code,
        new HttpEntity<>(manufacturer(
            com.salesmanager.core.model.catalog.product.manufacturer.Manufacturer.DEFAULT_MANUFACTURER),
            getHeader()), String.class);
    assertTrue(manufacturer.getStatusCode().is2xxSuccessful(), String.valueOf(manufacturer));
  }

  private void createProduct(String sku, String slug, boolean available) {
    PersistableProduct product = product(sku);
    product.getDescriptions().get(0).setFriendlyUrl(slug);
    postProduct(product, sku, available, Constants.DEFAULT_STORE);
  }

  private void postProduct(PersistableProduct product, String sku, boolean available, String store) {
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
        "/api/v1/private/product?store=" + store,
        new HttpEntity<>(product, getHeader()), Entity.class);
    assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response));
  }
}
