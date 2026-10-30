package com.salesmanager.test.shop.integration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.salesmanager.shop.model.catalog.category.PersistableCategory;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/** A category description is cleaned on save, so the public category API never returns a script payload. */
class CategoryDescriptionSanitizingIntegrationTest extends ServicesTestSupport {

	@Test
	void categoryDescriptionIsSanitisedOnSave() {
		PersistableCategory category = category("xss-category", "xss-category");
		category.getDescriptions().get(0).setDescription("<p>Oak furniture</p><img src=x onerror=alert(9)><script>alert(1)</script>");
		ResponseEntity<String> created = testRestTemplate.exchange("/api/v1/private/category", HttpMethod.POST,
				new HttpEntity<>(category, getHeader()), String.class);
		assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.toString());

		ResponseEntity<String> read = testRestTemplate.getForEntity("/api/v1/category/xss-category?lang=en", String.class);
		assertEquals(HttpStatus.OK, read.getStatusCode(), read.toString());
		assertTrue(read.getBody().contains("Oak furniture"), read.getBody());
		assertFalse(read.getBody().contains("onerror"), read.getBody());
		assertFalse(read.getBody().contains("<script"), read.getBody());
	}
}
