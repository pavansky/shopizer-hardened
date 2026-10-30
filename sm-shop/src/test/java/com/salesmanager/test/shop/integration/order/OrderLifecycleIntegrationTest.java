package com.salesmanager.test.shop.integration.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * The order lifecycle over HTTP on the default (H2) profile, with money-order
 * checkouts like scripts/demo-checkout.sh: legal transitions, the status update
 * limited to fulfilment, cancel with refund and restock, refund restock, and
 * the payment re-check guard.
 */
public class OrderLifecycleIntegrationTest extends ServicesTestSupport {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final long UNKNOWN_ORDER = 987654321L;

	private HttpHeaders admin;

	private Map<String, Object> call(HttpMethod method, String url, String body, HttpStatus expected)
			throws Exception {
		ResponseEntity<String> r = testRestTemplate.exchange(url, method, new HttpEntity<>(body, admin), String.class);
		assertEquals(expected, r.getStatusCode(), url + " -> " + r.getBody());
		return r.getBody() == null || r.getBody().isEmpty() ? null : JSON.readValue(r.getBody(), Map.class);
	}

	/** Catalog with one product of 5 units and a money-order checkout of one unit; returns the order id. */
	private long setUpOrder(String sku) throws Exception {
		admin = getHeader();
		call(HttpMethod.POST, "/api/v1/private/modules/payment",
				"{\"code\":\"moneyorder\",\"active\":true,\"defaultSelected\":true,"
						+ "\"integrationKeys\":{\"address\":\"1 Main St\"},\"integrationOptions\":{}}",
				HttpStatus.OK);
		Map<String, Object> cat = call(HttpMethod.POST, "/api/v1/private/category", "{\"code\":\"" + sku
				+ "-cat\",\"visible\":true,\"descriptions\":[{\"language\":\"en\",\"name\":\"" + sku
				+ "\",\"friendlyUrl\":\"" + sku + "-cat\"}]}", HttpStatus.CREATED);
		testRestTemplate.exchange("/api/v2/private/product", HttpMethod.POST, new HttpEntity<>("{\"identifier\":\""
				+ sku + "\",\"sku\":\"" + sku + "\",\"visible\":true,\"shipeable\":false,\"canBePurchased\":true,"
				+ "\"price\":249.00,\"quantity\":5,\"dateAvailable\":\"2026-10-01\",\"categories\":[{\"id\":"
				+ cat.get("id") + "}],\"type\":\"GENERAL\",\"descriptions\":[{\"language\":\"en\",\"name\":\"" + sku
				+ "\",\"description\":\"x\",\"friendlyUrl\":\"" + sku + "\",\"title\":\"" + sku + "\"}]}", admin),
				String.class);
		assertEquals(5, stock(sku));

		HttpHeaders json = new HttpHeaders();
		json.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> cart = testRestTemplate.postForEntity("/api/v1/cart",
				new HttpEntity<>("{\"product\":\"" + sku + "\",\"quantity\":1}", json), String.class);
		String code = (String) JSON.readValue(cart.getBody(), Map.class).get("code");
		ResponseEntity<String> order = testRestTemplate.postForEntity("/api/v1/cart/" + code + "/checkout",
				new HttpEntity<>("{\"customer\":{\"emailAddress\":\"" + sku + "@example.com\",\"firstName\":\"T\","
						+ "\"lastName\":\"B\",\"billing\":{\"firstName\":\"T\",\"lastName\":\"B\",\"address\":\"1 Main St\","
						+ "\"city\":\"Toronto\",\"postalCode\":\"M5V2T6\",\"country\":\"CA\",\"zone\":\"ON\","
						+ "\"phone\":\"5555555555\"}},\"payment\":{\"paymentType\":\"MONEYORDER\","
						+ "\"transactionType\":\"CAPTURE\",\"paymentModule\":\"moneyorder\",\"amount\":\"249.00\"},"
						+ "\"currency\":\"CAD\",\"customerAgreement\":true}", json),
				String.class);
		assertEquals(HttpStatus.OK, order.getStatusCode(), order.getBody());
		assertEquals(4, stock(sku));
		return ((Number) JSON.readValue(order.getBody(), Map.class).get("id")).longValue();
	}

	private int stock(String sku) throws Exception {
		ResponseEntity<String> r = testRestTemplate.getForEntity("/api/v1/product/" + sku + "?store=DEFAULT",
				String.class);
		return ((Number) JSON.readValue(r.getBody(), Map.class).get("quantity")).intValue();
	}

	private String status(long id) throws Exception {
		return (String) call(HttpMethod.GET, "/api/v1/private/orders/" + id, null, HttpStatus.OK).get("orderStatus");
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusUpdateCancelAndTransitions() throws Exception {
		long id = setUpOrder("lifecycle-cancel");
		String base = "/api/v1/private/orders/" + id;

		Map<String, Object> t = call(HttpMethod.GET, base + "/transitions", null, HttpStatus.OK);
		assertEquals("ORDERED", t.get("status"));
		assertEquals(java.util.Arrays.asList("PROCESSED", "DELIVERED"), (List<String>) t.get("statusUpdates"));
		assertEquals(Boolean.TRUE, t.get("cancelable"));

		Map<String, Object> refused = call(HttpMethod.PUT, base + "/status", "\"CANCELED\"", HttpStatus.CONFLICT);
		assertTrue(((String) refused.get("message")).contains("/cancel"));
		call(HttpMethod.PUT, base + "/status", "\"REFUNDED\"", HttpStatus.CONFLICT);
		call(HttpMethod.PUT, base + "/status", "\"PROCESSED\"", HttpStatus.OK);
		assertEquals("PROCESSED", status(id));

		// a history note cannot change the status
		call(HttpMethod.POST, base + "/history", "{\"status\":\"DELIVERED\",\"comments\":\"x\"}", HttpStatus.CONFLICT);
		assertEquals("PROCESSED", status(id));

		Map<String, Object> canceled = call(HttpMethod.POST, base + "/cancel", "{\"comment\":\"customer asked\"}",
				HttpStatus.OK);
		assertEquals("CANCELED", canceled.get("status"));
		assertTrue(((String) canceled.get("message")).contains("refunded"));
		assertEquals("CANCELED", status(id));
		assertEquals(5, stock("lifecycle-cancel"));

		call(HttpMethod.POST, base + "/cancel", null, HttpStatus.CONFLICT);
		assertEquals(5, stock("lifecycle-cancel"));

		call(HttpMethod.POST, base + "/payment/recheck", null, HttpStatus.CONFLICT);
	}

	@Test
	public void fullRefundReturnsStockPartialDoesNot() throws Exception {
		long id = setUpOrder("lifecycle-refund");
		String base = "/api/v1/private/orders/" + id;

		call(HttpMethod.POST, base + "/refund", "{\"amount\":100.00}", HttpStatus.OK);
		assertEquals(4, stock("lifecycle-refund"));
		call(HttpMethod.POST, base + "/refund", "{}", HttpStatus.OK);
		assertEquals("REFUNDED", status(id));
		assertEquals(5, stock("lifecycle-refund"));
		// a refunded order is final: no cancel, no second restock
		call(HttpMethod.POST, base + "/cancel", null, HttpStatus.CONFLICT);
		assertEquals(5, stock("lifecycle-refund"));
	}

	@Test
	public void unknownOrderIsNotFound() throws Exception {
		admin = getHeader();
		String base = "/api/v1/private/orders/" + UNKNOWN_ORDER;
		call(HttpMethod.GET, base, null, HttpStatus.NOT_FOUND);
		call(HttpMethod.GET, base + "/transitions", null, HttpStatus.NOT_FOUND);
		call(HttpMethod.POST, base + "/cancel", null, HttpStatus.NOT_FOUND);
		call(HttpMethod.POST, base + "/payment/recheck", null, HttpStatus.NOT_FOUND);
		call(HttpMethod.PUT, base + "/status", "\"PROCESSED\"", HttpStatus.NOT_FOUND);
	}
}
