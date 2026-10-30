package com.salesmanager.shop.store.api.v1.product;

import java.security.Principal;
import java.util.List;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.salesmanager.core.business.services.catalog.product.ProductService;
import com.salesmanager.core.business.services.catalog.product.review.ProductReviewService;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.review.ProductReview;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.model.catalog.product.PersistableProductReview;
import com.salesmanager.shop.model.catalog.product.ReadableProductReview;
import com.salesmanager.shop.store.api.exception.UnauthorizedException;
import com.salesmanager.shop.store.controller.customer.facade.CustomerFacade;
import com.salesmanager.shop.store.controller.product.facade.ProductCommonFacade;

import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import springfox.documentation.annotations.ApiIgnore;

@Controller
@RequestMapping("/api/v1")
public class ProductReviewApi {

  @Inject private ProductCommonFacade productCommonFacade;

  @Inject private ProductService productService;

  @Inject private ProductReviewService productReviewService;

  @Inject private CustomerFacade customerFacade;

  /**
   * On customer facing /auth/ routes the reviewer is always the authenticated
   * customer; any customerId sent in the body is ignored so a customer cannot
   * post or edit reviews on behalf of someone else. Returns null when the
   * request is not on an /auth/ route (admin /private/ routes keep accepting
   * the customerId from the body). Throws when an /auth/ caller cannot be
   * resolved to a customer of this store.
   */
  Long resolveAuthenticatedCustomerId(HttpServletRequest request, MerchantStore merchantStore) {
    String uri = request.getRequestURI();
    if (uri == null || !uri.contains("/api/v1/auth/")) {
      return null;
    }
    Principal principal = request.getUserPrincipal();
    if (principal == null || principal.getName() == null) {
      throw new UnauthorizedException("Authenticated customer required");
    }
    Customer customer = customerFacade.getCustomerByUserName(principal.getName(), merchantStore);
    if (customer == null) {
      throw new UnauthorizedException("Authenticated customer required");
    }
    return customer.getId();
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(ProductReviewApi.class);

  @RequestMapping(
      value = {
        "/private/products/{id}/reviews",
        "/auth/products/{id}/reviews",
        "/auth/products/{id}/reviews",
        "/auth/products/{id}/reviews"
      },
      method = RequestMethod.POST)
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  @ApiImplicitParams({
      @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
      @ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en")
  })
  public PersistableProductReview create(
      @PathVariable final Long id,
      @Valid @RequestBody PersistableProductReview review,
      @ApiIgnore MerchantStore merchantStore,
      @ApiIgnore Language language,
      HttpServletRequest request,
      HttpServletResponse response) {

    // Resolved outside the try block so authentication failures surface as 401
    // instead of being swallowed into a generic 503.
    Long authCustomerId = resolveAuthenticatedCustomerId(request, merchantStore);
    if (authCustomerId != null) {
      review.setCustomerId(authCustomerId);
    }
    // The product comes from the path, not the body, so the duplicate check
    // below looks at the product actually being reviewed.
    review.setProductId(id);

    try {
      // rating already exist
      ProductReview prodReview =
          productReviewService.getByProductAndCustomer(
              review.getProductId(), review.getCustomerId());
      if (prodReview != null) {
        response.sendError(500, "A review already exist for this customer and product");
        return null;
      }

      // rating maximum 5
      if (review.getRating() > Constants.MAX_REVIEW_RATING_SCORE) {
        response.sendError(503, "Maximum rating score is " + Constants.MAX_REVIEW_RATING_SCORE);
        return null;
      }

      review.setProductId(id);

      productCommonFacade.saveOrUpdateReview(review, merchantStore, language);

      return review;

    } catch (Exception e) {
      LOGGER.error("Error while saving product review", e);
      try {
        response.sendError(503, "Error while saving product review" + e.getMessage());
      } catch (Exception ignore) {
      }

      return null;
    }
  }

  @RequestMapping(value = "/product/{id}/reviews", method = RequestMethod.GET)
  @ResponseStatus(HttpStatus.OK)
  @ResponseBody
  @ApiImplicitParams({
      @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
      @ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en")
  })
  public List<ReadableProductReview> getAll(
      @PathVariable final Long id,
      @ApiIgnore MerchantStore merchantStore,
      @ApiIgnore Language language,
      HttpServletResponse response) {

    try {
      // Only products of the requested store; another store's product id is
      // reported as not found so its reviews are not listed here.
      Product product = productService.findOne(id, merchantStore);

      if (product == null) {
        response.sendError(404, "Product id " + id + " does not exists");
        return null;
      }

      List<ReadableProductReview> reviews =
    		  productCommonFacade.getProductReviews(product, merchantStore, language);

      return reviews;

    } catch (Exception e) {
      LOGGER.error("Error while getting product reviews", e);
      try {
        response.sendError(503, "Error while getting product reviews" + e.getMessage());
      } catch (Exception ignore) {
      }

      return null;
    }
  }

  @RequestMapping(
      value = {
        "/private/products/{id}/reviews/{reviewid}",
        "/auth/products/{id}/reviews/{reviewid}"
      },
      method = RequestMethod.PUT)
  @ResponseStatus(HttpStatus.OK)
  @ResponseBody
  @ApiImplicitParams({
      @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
      @ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en")
  })
  public PersistableProductReview update(
      @PathVariable final Long id,
      @PathVariable(name = "reviewid") final Long reviewId,
      @Valid @RequestBody PersistableProductReview review,
      @ApiIgnore MerchantStore merchantStore,
      @ApiIgnore Language language,
      HttpServletRequest request,
      HttpServletResponse response) {

    Long authCustomerId = resolveAuthenticatedCustomerId(request, merchantStore);

    try {
      ProductReview prodReview = productReviewService.getById(reviewId);
      if (prodReview == null || productService.findOne(id, merchantStore) == null) {
        response.sendError(404, "Product review with id " + reviewId + " does not exist");
        return null;
      }

      // Ownership is checked against the authenticated customer on /auth/
      // routes; the body customerId is not trusted. A review owned by someone
      // else (or attached to another product) is reported as not found so its
      // existence is not disclosed.
      Long ownerId = authCustomerId != null ? authCustomerId : review.getCustomerId();
      if (ownerId == null
          || prodReview.getCustomer() == null
          || prodReview.getCustomer().getId().longValue() != ownerId.longValue()
          || prodReview.getProduct() == null
          || prodReview.getProduct().getId().longValue() != id.longValue()) {
        response.sendError(404, "Product review with id " + reviewId + " does not exist");
        return null;
      }
      review.setCustomerId(ownerId);
      review.setId(reviewId);

      // rating maximum 5
      if (review.getRating() > Constants.MAX_REVIEW_RATING_SCORE) {
        response.sendError(503, "Maximum rating score is " + Constants.MAX_REVIEW_RATING_SCORE);
        return null;
      }

      review.setProductId(id);

      productCommonFacade.saveOrUpdateReview(review, merchantStore, language);

      return review;

    } catch (Exception e) {
      LOGGER.error("Error while saving product review", e);
      try {
        response.sendError(503, "Error while saving product review" + e.getMessage());
      } catch (Exception ignore) {
      }

      return null;
    }
  }

  @RequestMapping(
      value = {
        "/private/products/{id}/reviews/{reviewid}",
        "/auth/products/{id}/reviews/{reviewid}"
      },
      method = RequestMethod.DELETE)
  @ResponseStatus(HttpStatus.OK)
  @ResponseBody
  @ApiImplicitParams({
      @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
      @ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en")
  })
  public void delete(
      @PathVariable final Long id,
      @PathVariable(name = "reviewid") final Long reviewId,
      @ApiIgnore MerchantStore merchantStore,
      @ApiIgnore Language language,
      HttpServletRequest request,
      HttpServletResponse response) {

    Long authCustomerId = resolveAuthenticatedCustomerId(request, merchantStore);

    try {
      ProductReview prodReview = productReviewService.getById(reviewId);
      // The product in the path must belong to the requested store; together
      // with the product id check below this keeps a review of another store
      // out of reach.
      if (prodReview == null || productService.findOne(id, merchantStore) == null) {
        response.sendError(404, "Product review with id " + reviewId + " does not exist");
        return;
      }

      if (prodReview.getProduct().getId().longValue() != id.longValue()) {
        response.sendError(404, "Product review with id " + reviewId + " does not exist");
        return;
      }

      // Customers may only delete their own reviews on /auth/ routes.
      if (authCustomerId != null
          && (prodReview.getCustomer() == null
              || prodReview.getCustomer().getId().longValue() != authCustomerId.longValue())) {
        response.sendError(404, "Product review with id " + reviewId + " does not exist");
        return;
      }

      productCommonFacade.deleteReview(prodReview, merchantStore, language);

    } catch (Exception e) {
      LOGGER.error("Error while deleting product review", e);
      try {
        response.sendError(503, "Error while deleting product review" + e.getMessage());
      } catch (Exception ignore) {
      }

      return;
    }
  }
}
