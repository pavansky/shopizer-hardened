package com.salesmanager.test.shop.integration.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.modules.integration.payment.impl.PaymentActionRequiredException;
import com.salesmanager.core.business.services.payments.PaymentGatewayBusyException;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.catalog.product.ReadableProduct;
import com.salesmanager.shop.model.shoppingcart.PersistableShoppingCartItem;
import com.salesmanager.shop.model.shoppingcart.ReadableShoppingCart;
import com.salesmanager.shop.store.api.v1.payment.PaymentErrorResolver;
import com.salesmanager.shop.store.security.AuthenticationRequest;
import com.salesmanager.shop.store.security.AuthenticationResponse;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * The checkout answers for a payment that is not finished (409 payment_action_required)
 * and for a saturated gateway (503 with Retry-After), over HTTP on the guest and the
 * signed-in checkout, with the exceptions raised where the payment service raises
 * them. A retry with the same Idempotency-Key must reach the payment again rather
 * than be answered from the idempotency store.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class PaymentCheckoutAnswersIntegrationTest extends ServicesTestSupport {

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService realPaymentService;

	/** Spy on the real payment service, set into the order service for one test only. */
	private PaymentService paymentService;

	// The shared application context is kept (a second context cannot start: the
	// ehcache manager is a VM singleton), so the spy is swapped in and out by hand.
	@Before
	public void spyOnPayments() {
		paymentService = Mockito.spy((PaymentService) AopTestUtils.getUltimateTargetObject(realPaymentService));
		ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(orderService), "paymentService", paymentService);
	}

	@After
	public void restorePayments() {
		ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(orderService), "paymentService",
				realPaymentService);
	}

	private final ObjectMapper mapper = new ObjectMapper();

	/** Total of the last cart made by cart(), sent as the checkout amount. */
	private String total;

	private String cart(String sku) {
		ReadableProduct product = sampleProduct(sku);
		PersistableShoppingCartItem item = new PersistableShoppingCartItem();
		item.setProduct(product.getSku());
		item.setQuantity(1);
		ReadableShoppingCart cart = testRestTemplate
				.postForEntity("/api/v1/cart/", new HttpEntity<>(item, getHeader()), ReadableShoppingCart.class)
				.getBody();
		assertNotNull(cart);
		total = cart.getTotal().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
		return cart.getCode();
	}

	private String payment(String intentId) {
		return "\"payment\":{\"paymentType\":\"CREDITCARD\",\"transactionType\":\"CAPTURE\","
				+ "\"paymentModule\":\"stripe3\",\"paymentToken\":\"" + intentId + "\",\"amount\":\"" + total
				+ "\"},"
				+ "\"currency\":\"CAD\",\"customerAgreement\":true";
	}

	private String guestBody(String email, String intentId) {
		return "{\"customer\":{\"emailAddress\":\"" + email + "\",\"firstName\":\"Test\",\"lastName\":\"Buyer\","
				+ "\"billing\":{\"firstName\":\"Test\",\"lastName\":\"Buyer\",\"address\":\"1 Main St\","
				+ "\"city\":\"Toronto\",\"postalCode\":\"M5V2T6\",\"country\":\"CA\",\"zone\":\"ON\","
				+ "\"phone\":\"5555555555\"}}," + payment(intentId) + "}";
	}

	private ResponseEntity<String> post(String url, String body, String key, HttpHeaders headers) {
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", key);
		return testRestTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> guestCheckout(String cartCode, String body, String key) {
		return post("/api/v1/cart/" + cartCode + "/checkout", body, key, new HttpHeaders());
	}

	private HttpHeaders signedInCustomer(String email) {
		String register = "{\"emailAddress\":\"" + email + "\",\"password\":\"clear123\",\"gender\":\"M\","
				+ "\"language\":\"en\",\"storeCode\":\"DEFAULT\",\"billing\":{\"firstName\":\"Test\","
				+ "\"lastName\":\"Buyer\",\"address\":\"1 Main St\",\"city\":\"Toronto\",\"postalCode\":\"M5V2T6\","
				+ "\"country\":\"CA\",\"zone\":\"ON\",\"phone\":\"5555555555\"}}";
		HttpHeaders json = new HttpHeaders();
		json.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> registered = testRestTemplate.postForEntity("/api/v1/customer/register",
				new HttpEntity<>(register, json), String.class);
		assertTrue(registered.getBody(), registered.getStatusCode().is2xxSuccessful());
		AuthenticationResponse login = testRestTemplate.postForEntity("/api/v1/customer/login",
				new HttpEntity<>(new AuthenticationRequest(email, "clear123")), AuthenticationResponse.class)
				.getBody();
		HttpHeaders headers = new HttpHeaders();
		headers.add("Authorization", "Bearer " + login.getToken());
		return headers;
	}

	private JsonNode json(ResponseEntity<String> response) throws Exception {
		return mapper.readTree(response.getBody());
	}

	private void assertActionRequired(ResponseEntity<String> response) throws Exception {
		assertEquals(response.getBody(), HttpStatus.CONFLICT, response.getStatusCode());
		JsonNode body = json(response);
		assertEquals(PaymentErrorResolver.ACTION_REQUIRED_CODE, body.path("errorCode").asText());
		assertEquals("pi_it_secret", body.path("clientSecret").asText());
		assertEquals("requires_action", body.path("paymentIntentStatus").asText());
		assertTrue(body.path("orderId").asLong() > 0);
	}

	private void assertBusy(ResponseEntity<String> response) throws Exception {
		assertEquals(response.getBody(), HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertEquals("2", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		assertEquals(PaymentErrorResolver.GATEWAY_BUSY_CODE, json(response).path("errorCode").asText());
	}

	@Test
	public void guestCheckoutWaitingForThreeDSecureIs409AndTheRetryResumesTheSameOrder() throws Exception {
		doThrow(new PaymentActionRequiredException("pi_it_guest", "pi_it_secret", "requires_action"))
				.when(paymentService).processPayment(any(), any(), any(), anyList(), any());
		String cartCode = cart("payAnsGuest3ds");
		String body = guestBody("guest.3ds@example.com", "pi_it_guest");

		ResponseEntity<String> first = guestCheckout(cartCode, body, "it-guest-3ds");
		assertActionRequired(first);

		// same key after the challenge: answered by the payment again, for the same order
		ResponseEntity<String> retry = guestCheckout(cartCode, body, "it-guest-3ds");
		assertActionRequired(retry);
		assertEquals(json(first).path("orderId").asLong(), json(retry).path("orderId").asLong());
		verify(paymentService, times(2)).processPayment(any(), any(), any(), anyList(), any());
	}

	@Test
	public void guestCheckoutOnABusyGatewayIs503WithRetryAfterAndCanBeRetried() throws Exception {
		doThrow(new PaymentGatewayBusyException("stripe3", 2)).when(paymentService).processPayment(any(), any(),
				any(), anyList(), any());
		String cartCode = cart("payAnsGuestBusy");
		String body = guestBody("guest.busy@example.com", "pi_it_busy");

		assertBusy(guestCheckout(cartCode, body, "it-guest-busy"));
		assertBusy(guestCheckout(cartCode, body, "it-guest-busy"));
		verify(paymentService, times(2)).processPayment(any(), any(), any(), anyList(), any());
	}

	@Test
	public void signedInCheckoutGetsTheSameAnswers() throws Exception {
		HttpHeaders customer = signedInCustomer("signed.in.payment@example.com");

		doThrow(new PaymentActionRequiredException("pi_it_signed", "pi_it_secret", "requires_action"))
				.when(paymentService).processPayment(any(), any(), any(), anyList(), any());
		String cartCode = cart("payAnsSigned3ds");
		String body = "{" + payment("pi_it_signed") + "}";
		ResponseEntity<String> first = post("/api/v1/auth/cart/" + cartCode + "/checkout", body, "it-signed-3ds",
				customer);
		assertActionRequired(first);
		ResponseEntity<String> retry = post("/api/v1/auth/cart/" + cartCode + "/checkout", body, "it-signed-3ds",
				customer);
		assertActionRequired(retry);
		assertEquals(json(first).path("orderId").asLong(), json(retry).path("orderId").asLong());

		doThrow(new PaymentGatewayBusyException("stripe3", 2)).when(paymentService).processPayment(any(), any(),
				any(), anyList(), any());
		String busyCart = cart("payAnsSignedBusy");
		assertBusy(post("/api/v1/auth/cart/" + busyCart + "/checkout", "{" + payment("pi_it_signed_busy") + "}",
				"it-signed-busy", customer));
	}
}
