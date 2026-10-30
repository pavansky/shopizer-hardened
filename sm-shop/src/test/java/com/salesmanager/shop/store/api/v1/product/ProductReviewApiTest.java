package com.salesmanager.shop.store.api.v1.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.salesmanager.core.business.services.catalog.product.ProductService;
import com.salesmanager.core.business.services.catalog.product.review.ProductReviewService;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.review.ProductReview;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.model.catalog.product.PersistableProductReview;
import com.salesmanager.shop.store.api.exception.UnauthorizedException;
import com.salesmanager.shop.store.controller.customer.facade.CustomerFacade;
import com.salesmanager.shop.store.controller.product.facade.ProductCommonFacade;

@ExtendWith(MockitoExtension.class)
class ProductReviewApiTest {

  @Mock private ProductCommonFacade productCommonFacade;
  @Mock private ProductReviewService productReviewService;
  @Mock private CustomerFacade customerFacade;
  @Mock private ProductService productService;
  @Mock private CustomerService customerService;
  @InjectMocks private ProductReviewApi api;

  private final MerchantStore store = new MerchantStore();
  private final Language language = new Language();

  @BeforeEach
  void setUp() {
    store.setId(1);
  }

  private MockHttpServletRequest authRequest(String uri, String user) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setUserPrincipal(() -> user);
    return request;
  }

  private Customer customer(long id) {
    Customer c = new Customer();
    c.setId(id);
    return c;
  }

  @Test
  void createIgnoresBodyCustomerIdOnAuthRoute() throws Exception {
    when(customerFacade.getCustomerByUserName("alice", store)).thenReturn(customer(7L));
    when(productService.findOne(5L, store)).thenReturn(new Product());
    PersistableProductReview review = new PersistableProductReview();
    review.setCustomerId(99L); // someone else
    review.setRating(4d);

    api.create(5L, review, store, language,
        authRequest("/api/v1/auth/products/5/reviews", "alice"), new MockHttpServletResponse());

    assertEquals(Long.valueOf(7L), review.getCustomerId());
    assertEquals(Long.valueOf(5L), review.getProductId());
    verify(productReviewService).getByProductAndCustomer(5L, 7L);
    verify(productCommonFacade).saveOrUpdateReview(review, store, language);
  }

  @Test
  void createWithoutPrincipalOnAuthRouteIsRejected() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/products/5/reviews");
    assertThrows(UnauthorizedException.class, () -> api.create(5L, new PersistableProductReview(),
        store, language, request, new MockHttpServletResponse()));
  }

  @Test
  void updateOfAnotherCustomersReviewReturns404AndDoesNotSave() throws Exception {
    when(customerFacade.getCustomerByUserName("alice", store)).thenReturn(customer(7L));
    ProductReview existing = new ProductReview();
    existing.setCustomer(customer(99L));
    Product product = new Product();
    product.setId(5L);
    existing.setProduct(product);
    when(productReviewService.getById(11L)).thenReturn(existing);
    when(productService.findOne(5L, store)).thenReturn(product);

    PersistableProductReview review = new PersistableProductReview();
    review.setCustomerId(99L); // forged to match the owner
    review.setRating(1d);
    MockHttpServletResponse response = new MockHttpServletResponse();

    api.update(5L, 11L, review, store, language,
        authRequest("/api/v1/auth/products/5/reviews/11", "alice"), response);

    assertEquals(404, response.getStatus());
    verify(productCommonFacade, never()).saveOrUpdateReview(any(), any(), any());
  }

  @Test
  void updateOfOwnReviewSaves() throws Exception {
    when(customerFacade.getCustomerByUserName("alice", store)).thenReturn(customer(7L));
    ProductReview existing = new ProductReview();
    existing.setCustomer(customer(7L));
    Product product = new Product();
    product.setId(5L);
    existing.setProduct(product);
    when(productReviewService.getById(11L)).thenReturn(existing);
    when(productService.findOne(5L, store)).thenReturn(product);

    PersistableProductReview review = new PersistableProductReview();
    review.setRating(3d);

    api.update(5L, 11L, review, store, language,
        authRequest("/api/v1/auth/products/5/reviews/11", "alice"), new MockHttpServletResponse());

    assertEquals(Long.valueOf(7L), review.getCustomerId());
    assertEquals(Long.valueOf(11L), review.getId());
    verify(productCommonFacade).saveOrUpdateReview(eq(review), eq(store), eq(language));
  }

  @Test
  void reviewsOfAnotherStoresProductAreNotListed() throws Exception {
    // findOne is store scoped: another store's product id resolves to nothing
    when(productService.findOne(5L, store)).thenReturn(null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    api.getAll(5L, store, language, response);

    assertEquals(404, response.getStatus());
    verify(productCommonFacade, never()).getProductReviews(any(), any(), any());
    verify(productService, never()).getById(any(Long.class));
  }

  @Test
  void reviewOfAnotherStoresProductCannotBeDeleted() throws Exception {
    ProductReview existing = new ProductReview();
    existing.setCustomer(customer(99L));
    Product product = new Product();
    product.setId(5L);
    existing.setProduct(product);
    when(productReviewService.getById(11L)).thenReturn(existing);
    when(productService.findOne(5L, store)).thenReturn(null);
    MockHttpServletResponse response = new MockHttpServletResponse();

    api.delete(5L, 11L, store, language,
        new MockHttpServletRequest("DELETE", "/api/v1/private/products/5/reviews/11"), response);

    assertEquals(404, response.getStatus());
    verify(productCommonFacade, never()).deleteReview(any(), any(), any());
  }

  @Test
  void reviewOfAnotherStoresProductCannotBeUpdated() throws Exception {
    ProductReview existing = new ProductReview();
    existing.setCustomer(customer(99L));
    Product product = new Product();
    product.setId(5L);
    existing.setProduct(product);
    when(productReviewService.getById(11L)).thenReturn(existing);
    when(productService.findOne(5L, store)).thenReturn(null);
    PersistableProductReview review = new PersistableProductReview();
    review.setCustomerId(99L);
    review.setRating(2d);
    MockHttpServletResponse response = new MockHttpServletResponse();

    api.update(5L, 11L, review, store, language,
        new MockHttpServletRequest("PUT", "/api/v1/private/products/5/reviews/11"), response);

    assertEquals(404, response.getStatus());
    verify(productCommonFacade, never()).saveOrUpdateReview(any(), any(), any());
  }

  @Test
  void createOnAnotherStoresProductReturns404AndDoesNotSave() throws Exception {
    when(productService.findOne(5L, store)).thenReturn(null);
    PersistableProductReview review = new PersistableProductReview();
    review.setCustomerId(7L);
    review.setRating(4d);
    MockHttpServletResponse response = new MockHttpServletResponse();

    api.create(5L, review, store, language,
        new MockHttpServletRequest("POST", "/api/v1/private/products/5/reviews"), response);

    assertEquals(404, response.getStatus());
    verify(productCommonFacade, never()).saveOrUpdateReview(any(), any(), any());
  }

  @Test
  void createByAReviewerOfAnotherStoreReturns404AndDoesNotSave() throws Exception {
    when(productService.findOne(5L, store)).thenReturn(new Product());
    when(customerService.getById(42L, store)).thenReturn(null);
    PersistableProductReview review = new PersistableProductReview();
    review.setCustomerId(42L);
    review.setRating(4d);
    MockHttpServletResponse response = new MockHttpServletResponse();

    api.create(5L, review, store, language,
        new MockHttpServletRequest("POST", "/api/v1/private/products/5/reviews"), response);

    assertEquals(404, response.getStatus());
    verify(productCommonFacade, never()).saveOrUpdateReview(any(), any(), any());
  }

  @Test
  void createIgnoresAReviewIdInTheBody() throws Exception {
    when(productService.findOne(5L, store)).thenReturn(new Product());
    when(customerService.getById(7L, store)).thenReturn(customer(7L));
    PersistableProductReview review = new PersistableProductReview();
    review.setId(11L); // another review, possibly of another store
    review.setCustomerId(7L);
    review.setRating(4d);
    Long[] idAtSave = new Long[] {-1L};
    org.mockito.Mockito.doAnswer(inv -> {
      idAtSave[0] = ((PersistableProductReview) inv.getArgument(0)).getId();
      return null;
    }).when(productCommonFacade).saveOrUpdateReview(any(), eq(store), eq(language));

    api.create(5L, review, store, language,
        new MockHttpServletRequest("POST", "/api/v1/private/products/5/reviews"), new MockHttpServletResponse());

    assertEquals(null, idAtSave[0]);
  }
}
