package com.salesmanager.test.shop.integration.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;

import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.catalog.product.ReadableProduct;
import com.salesmanager.shop.model.shoppingcart.PersistableShoppingCartItem;
import com.salesmanager.shop.model.shoppingcart.ReadableShoppingCart;
import com.salesmanager.shop.store.api.exception.ErrorEntity;
import com.salesmanager.shop.store.api.v1.payment.PaymentErrorResolver;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Payment edge cases over the running application: the wiring that unit tests
 * cannot show.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class PaymentEdgeIntegrationTest extends ServicesTestSupport {

	@Autowired
	private DispatcherServlet dispatcherServlet;

	@Test
	@SuppressWarnings("unchecked")
	public void paymentErrorResolverRunsBeforeTheExceptionHandlers() {
		// the servlet initialises its resolvers on the first request
		fetchStore();
		List<HandlerExceptionResolver> resolvers = (List<HandlerExceptionResolver>) ReflectionTestUtils
				.getField(dispatcherServlet, "handlerExceptionResolvers");

		int payment = -1;
		int handlers = -1;
		for (int i = 0; i < resolvers.size(); i++) {
			if (resolvers.get(i) instanceof PaymentErrorResolver) {
				payment = i;
			}
			if (resolvers.get(i) instanceof HandlerExceptionResolverComposite) {
				handlers = i;
			}
		}
		assertTrue("PaymentErrorResolver is registered", payment >= 0);
		assertTrue("PaymentErrorResolver comes before the @ExceptionHandler resolvers", payment < handlers);
	}

	@Test
	public void paymentInitRejectsAnAmountThatIsNotTheCartTotal() {
		ReadableProduct product = sampleProduct("payEdgeInit");
		PersistableShoppingCartItem item = new PersistableShoppingCartItem();
		item.setProduct(product.getSku());
		item.setQuantity(1);
		ReadableShoppingCart cart = testRestTemplate
				.postForEntity("/api/v1/cart/", new HttpEntity<>(item, getHeader()), ReadableShoppingCart.class)
				.getBody();

		org.springframework.http.HttpHeaders headers = getHeader();
		headers.setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"paymentModule\":\"stripe3\",\"paymentType\":\"CREDITCARD\","
				+ "\"transactionType\":\"INIT\",\"amount\":\"0.01\"}";
		ResponseEntity<ErrorEntity> response = testRestTemplate.postForEntity(
				"/api/v1/cart/" + cart.getCode() + "/payment/init", new HttpEntity<>(body, headers), ErrorEntity.class);

		// the cart holds one product at 10.00: a client total of 0.01 never reaches Stripe
		assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
		assertTrue(response.getBody().getMessage().contains("does not match the cart total"));
	}
}
