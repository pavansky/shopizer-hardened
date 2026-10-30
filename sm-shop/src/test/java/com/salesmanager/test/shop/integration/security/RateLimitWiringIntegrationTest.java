package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.salesmanager.shop.store.security.AuthenticationRequest;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * The rate limits as wired in the running application: the servlet filter on
 * the real controller paths, and the HTTP Basic limit inside the security
 * chains drawing from the same login budget. Runs in its own surefire
 * execution (see sm-shop/pom.xml) with the limits switched on; the other
 * integration tests run with them off.
 */
@TestPropertySource(properties = { "shopizer.ratelimit.enabled=true", "shopizer.ratelimit.login.capacity=3",
		"shopizer.ratelimit.login.period-seconds=600", "shopizer.ratelimit.password-reset.capacity=2",
		"shopizer.ratelimit.password-reset.period-seconds=600" })
class RateLimitWiringIntegrationTest extends ServicesTestSupport {

	private static final String ADMIN_BASIC = "Basic YWRtaW5Ac2hvcGl6ZXIuY29tOnBhc3N3b3Jk"; // admin@shopizer.com:password
	private static final String BOGUS_BASIC = "Basic bm9ib2R5QHNob3BpemVyLmNvbTp4"; // nobody@shopizer.com:x

	private ResponseEntity<String> login(String password, String basic) {
		HttpHeaders headers = new HttpHeaders();
		if (basic != null) {
			headers.set(HttpHeaders.AUTHORIZATION, basic);
		}
		return testRestTemplate.exchange("/api/v1/private/login", HttpMethod.POST,
				new HttpEntity<>(new AuthenticationRequest("admin@shopizer.com", password), headers), String.class);
	}

	private ResponseEntity<String> servicesWithBasic() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, BOGUS_BASIC);
		return testRestTemplate.exchange("/services/private/system/optin", HttpMethod.POST,
				new HttpEntity<>("{}", headers), String.class);
	}

	@Test
	void loginEndpointAndHttpBasicShareOneBudgetAndTheNextRequestGets429() {
		// a login that also carries Basic credentials is charged once
		assertEquals(HttpStatus.OK, login("password", ADMIN_BASIC).getStatusCode());
		// Basic on the legacy API spends the same budget
		assertEquals(HttpStatus.UNAUTHORIZED, servicesWithBasic().getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, login("not-the-password", null).getStatusCode());

		ResponseEntity<String> limited = login("password", null);
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());
		String retryAfter = limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
		assertNotNull(retryAfter);
		assertTrue(Long.parseLong(retryAfter) >= 1);

		assertEquals(HttpStatus.TOO_MANY_REQUESTS, servicesWithBasic().getStatusCode());
	}

	@Test
	void passwordResetRequestGets429AfterTheLimit() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> body = new HttpEntity<>(
				"{\"username\":\"someone@test.com\",\"returnUrl\":\"http://localhost:4200\"}", headers);
		for (int i = 0; i < 2; i++) {
			assertEquals(HttpStatus.OK, testRestTemplate
					.postForEntity("/api/v1/user/password/reset/request", body, String.class).getStatusCode());
		}
		ResponseEntity<String> limited = testRestTemplate.postForEntity("/api/v1/customer/password/reset/request",
				body, String.class);
		assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());
		assertNotNull(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
	}
}
