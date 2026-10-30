package com.salesmanager.test.shop.integration.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.shop.model.references.PersistableAddress;
import com.salesmanager.shop.model.store.PersistableMerchantStore;
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

	@Autowired
	private JdbcTemplate jdbc;

	private Map<String, Object> call(HttpMethod method, String url, String body, HttpStatus expected)
			throws Exception {
		ResponseEntity<String> r = testRestTemplate.exchange(url, method, new HttpEntity<>(body, admin), String.class);
		assertEquals(expected, r.getStatusCode(), url + " -> " + r.getBody());
		return r.getBody() == null || r.getBody().isEmpty() ? null : JSON.readValue(r.getBody(), Map.class);
	}

	/** Catalog with one product of 5 units and a money-order checkout of one unit; returns the order id. */
	private long setUpOrder(String sku) throws Exception {
		return setUpOrder(sku, "B", "5555555555");
	}

	private long setUpOrder(String sku, String lastName, String phone) throws Exception {
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
						+ "\"lastName\":\"" + lastName + "\",\"billing\":{\"firstName\":\"T\",\"lastName\":\"" + lastName
						+ "\",\"address\":\"1 Main St\","
						+ "\"city\":\"Toronto\",\"postalCode\":\"M5V2T6\",\"country\":\"CA\",\"zone\":\"ON\","
						+ "\"phone\":\"" + phone + "\"}},\"payment\":{\"paymentType\":\"MONEYORDER\","
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

	@SuppressWarnings("unchecked")
	private List<Long> listedIds(String query) throws Exception {
		Map<String, Object> list = call(HttpMethod.GET, "/api/v1/private/orders?" + query, null, HttpStatus.OK);
		List<Long> ids = new java.util.ArrayList<>();
		List<Map<String, Object>> orders = (List<Map<String, Object>>) list.get("orders");
		if (orders == null) {
			return ids; // the list leaves "orders" out when nothing matches
		}
		for (Map<String, Object> o : orders) {
			ids.add(((Number) o.get("id")).longValue());
		}
		return ids;
	}

	/** Creates a second store and moves the order and its customer there, as if bought in that store. */
	private void moveToAnotherStore(long orderId, long customerId) {
		PersistableAddress address = new PersistableAddress();
		address.setAddress("2 Other St");
		address.setPostalCode("12345");
		address.setCountry("US");
		address.setCity("Miami");
		address.setStateProvince("FL");
		PersistableMerchantStore other = new PersistableMerchantStore();
		other.setCode("lcother");
		other.setName("lcother");
		other.setCurrency("CAD");
		other.setDefaultLanguage("en");
		other.setEmail("other@example.com");
		other.setPhone("444-555-6666");
		other.setSupportedLanguages(Arrays.asList("en"));
		other.setAddress(address);
		ResponseEntity<String> created = testRestTemplate.exchange("/api/v1/private/store/", HttpMethod.POST,
				new HttpEntity<>(other, admin), String.class);
		assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
		Integer otherStore = jdbc.queryForObject(
				"select MERCHANT_ID from SALESMANAGER.MERCHANT_STORE where STORE_CODE = 'lcother'", Integer.class);
		assertEquals(1, jdbc.update("update SALESMANAGER.ORDERS set MERCHANTID = ? where ORDER_ID = ?", otherStore,
				orderId));
		assertEquals(1, jdbc.update("update SALESMANAGER.CUSTOMER set MERCHANT_ID = ? where CUSTOMER_ID = ?", otherStore,
				customerId));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void orderOfAnotherStoreIsNotFound() throws Exception {
		long id = setUpOrder("lifecycle-foreign", "Farstore", "4161230987");
		String base = "/api/v1/private/orders/" + id;
		Map<String, Object> order = call(HttpMethod.GET, base, null, HttpStatus.OK);
		long customerId = ((Number) ((Map<String, Object>) order.get("customer")).get("id")).longValue();
		// while the order is in the default store, the list filters find it
		assertTrue(listedIds("name=Farstore").contains(id));
		assertTrue(listedIds("phone=4161230987").contains(id));

		moveToAnotherStore(id, customerId);

		// the default-store admin cannot see or change it
		call(HttpMethod.GET, base, null, HttpStatus.NOT_FOUND);
		call(HttpMethod.GET, base + "/transitions", null, HttpStatus.NOT_FOUND);
		call(HttpMethod.POST, base + "/cancel", null, HttpStatus.NOT_FOUND);
		call(HttpMethod.POST, base + "/payment/recheck", null, HttpStatus.NOT_FOUND);
		call(HttpMethod.PUT, base + "/status", "\"PROCESSED\"", HttpStatus.NOT_FOUND);
		assertEquals(HttpStatus.NOT_FOUND, testRestTemplate.exchange("/api/v1/private/orders/customers/" + customerId,
				HttpMethod.GET, new HttpEntity<>(admin), String.class).getStatusCode());
		// the name and phone filters stay inside the store (an unparenthesised OR used to leak)
		assertFalse(listedIds("name=Farstore").contains(id));
		assertFalse(listedIds("phone=4161230987").contains(id));
		assertFalse(listedIds("").contains(id));
		// and nothing about it changed
		assertEquals("ORDERED",
				jdbc.queryForObject("select ORDER_STATUS from SALESMANAGER.ORDERS where ORDER_ID = ?", String.class, id));
		assertEquals(4, stock("lifecycle-foreign"));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void reviewOrderIsSettledByRecheckOrCancelNotByStatusUpdate() throws Exception {
		long id = setUpOrder("lifecycle-review");
		String base = "/api/v1/private/orders/" + id;
		// what scripts/simulate-stuck-order.sh leaves behind once the reconciler gave up: no payment record
		jdbc.update("update SALESMANAGER.ORDERS set ORDER_STATUS = 'PAYMENT_REVIEW' where ORDER_ID = ?", id);
		jdbc.update("delete from SALESMANAGER.SM_TRANSACTION where ORDER_ID = ?", id);
		jdbc.update("update SALESMANAGER.PAYMENT_ATTEMPT set STATUS = 'UNKNOWN' where ORDER_ID = ?", id);

		Map<String, Object> t = call(HttpMethod.GET, base + "/transitions", null, HttpStatus.OK);
		assertEquals("PAYMENT_REVIEW", t.get("status"));
		assertTrue(((List<String>) t.get("statusUpdates")).isEmpty());
		assertEquals(Boolean.TRUE, t.get("paymentRecheck"));
		assertEquals(Boolean.TRUE, t.get("cancelable"));

		Map<String, Object> refused = call(HttpMethod.PUT, base + "/status", "\"ORDERED\"", HttpStatus.CONFLICT);
		assertTrue(((String) refused.get("message")).contains("/payment/recheck"));
		call(HttpMethod.PUT, base + "/status", "\"PROCESSED\"", HttpStatus.CONFLICT);
		assertEquals("PAYMENT_REVIEW", status(id));
		assertEquals(4, stock("lifecycle-review"));

		Map<String, Object> canceled = call(HttpMethod.POST, base + "/cancel", null, HttpStatus.OK);
		assertEquals("CANCELED", canceled.get("status"));
		assertEquals(5, stock("lifecycle-review"));
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
