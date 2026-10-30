package com.salesmanager.test.shop.integration.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.Collections;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.observability.KnownStores;
import com.salesmanager.shop.observability.PaymentReviewGauge;
import com.salesmanager.test.shop.common.ServicesTestSupport;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The request id header and the operational meters on the running application: the
 * request id filter is installed, the store tag reaches http_server_requests, the gauges
 * are exported, and the payment aspect wraps the real PaymentService bean.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class ObservabilityIntegrationTest extends ServicesTestSupport {

	@Inject
	private KnownStores knownStores;

	@Inject
	private PaymentReviewGauge paymentReviewGauge;

	@Inject
	private PaymentService paymentService;

	@Inject
	private MerchantStoreService merchantStoreService;

	@Inject
	private MeterRegistry registry;

	@Test
	public void responsesCarryTheRequestId() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Request-Id", "it-req-1");
		ResponseEntity<String> echoed = testRestTemplate.exchange("/actuator/health", HttpMethod.GET,
				new HttpEntity<>(headers), String.class);
		assertEquals("it-req-1", echoed.getHeaders().getFirst("X-Request-Id"));

		ResponseEntity<String> created = testRestTemplate.getForEntity("/actuator/health", String.class);
		assertNotNull(created.getHeaders().getFirst("X-Request-Id"));
	}

	@Test
	public void operationalMetricsAreExported() throws Exception {
		knownStores.refresh();
		paymentReviewGauge.refresh();
		testRestTemplate.getForEntity("/api/v1/category?store=DEFAULT&lang=en", String.class);
		testRestTemplate.getForEntity("/api/v1/category?store=no-such-store-" + System.nanoTime(), String.class);

		// a payment with an unregistered module is refused before any gateway is called
		Payment payment = new Payment();
		payment.setModuleName("no-such-module");
		Order order = new Order();
		order.setTotal(new BigDecimal("1.00"));
		try {
			paymentService.processPayment(new Customer(), merchantStoreService.getByCode("DEFAULT"), payment,
					Collections.emptyList(), order);
			fail("an unregistered payment module must be refused");
		} catch (ServiceException expected) {
			// counted as failed
		}

		// the test profile disables the prometheus endpoint, so read the registry it would render
		assertEquals(0.0, registry.get("shopizer.backup.last.success.timestamp.seconds").gauge().value(), 0);
		assertNotNull(registry.get("shopizer.orders.payment.review").tag("store", "DEFAULT").gauge());
		assertTrue(registry.find("http.server.requests").tag("store", "DEFAULT").timer() != null);
		assertTrue(registry.find("http.server.requests").tag("store", "none").timer() != null);
		assertTrue(registry.find("http.server.requests").timers().stream()
				.noneMatch(t -> String.valueOf(t.getId().getTag("store")).startsWith("no-such-store")));
		assertTrue(registry.get("shopizer.payment.outcomes")
				.tags("store", "DEFAULT", "module", "other", "outcome", "failed").counter().count() >= 1);
	}

}
