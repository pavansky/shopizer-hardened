package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.salesmanager.test.shop.common.ServicesTestSupport;

/** Successful and refused API responses both carry the browser hardening headers. */
class ApiSecurityHeadersIntegrationTest extends ServicesTestSupport {

	private static void assertHardened(HttpHeaders headers) {
		assertEquals("default-src 'none'; frame-ancestors 'none'", headers.getFirst("Content-Security-Policy"));
		assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
		assertEquals("no-referrer", headers.getFirst("Referrer-Policy"));
	}

	@Test
	void publicAndRefusedResponsesCarryTheHeaders() {
		ResponseEntity<String> store = testRestTemplate.getForEntity("/api/v1/store/DEFAULT", String.class);
		assertEquals(HttpStatus.OK, store.getStatusCode());
		assertHardened(store.getHeaders());

		ResponseEntity<String> refused = testRestTemplate.getForEntity("/api/v1/private/orders", String.class);
		assertEquals(HttpStatus.UNAUTHORIZED, refused.getStatusCode());
		assertHardened(refused.getHeaders());
	}
}
