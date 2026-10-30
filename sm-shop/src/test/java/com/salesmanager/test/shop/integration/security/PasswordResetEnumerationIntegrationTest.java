package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.salesmanager.shop.store.security.abuse.PasswordResetResponse;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/** A password reset request gets the same status and body whether or not the account exists. */
class PasswordResetEnumerationIntegrationTest extends ServicesTestSupport {

	private ResponseEntity<String> request(String path, String username) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"username\":\"" + username + "\",\"returnUrl\":\"http://localhost:4200\"}";
		return testRestTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
	}

	private void assertSameAnswer(String path, String existing) {
		ResponseEntity<String> known = request(path, existing);
		ResponseEntity<String> unknown = request(path, "no-such-account-anywhere@test.com");
		assertEquals(HttpStatus.OK, known.getStatusCode());
		assertEquals(known.getStatusCode(), unknown.getStatusCode());
		assertEquals(known.getBody(), unknown.getBody());
		assertTrue(unknown.getBody().contains(PasswordResetResponse.MESSAGE));
	}

	@Test
	void userResetRequestDoesNotRevealWhetherTheAccountExists() {
		assertSameAnswer("/api/v1/user/password/reset/request", "admin@shopizer.com");
	}

	@Test
	void customerResetRequestDoesNotRevealWhetherTheAccountExists() {
		assertSameAnswer("/api/v1/customer/password/reset/request", "customer1@test.com");
	}
}
