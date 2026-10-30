package com.salesmanager.test.shop.integration.product;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Catalog reads through the whole filter chain: an anonymous read may be cached by a CDN,
 * Spring Security's no-store stays on everything authenticated and on errors, and an
 * unknown product slug is a 404.
 */
@RunWith(SpringRunner.class)
public class CatalogReadHttpTest extends ServicesTestSupport {

	private static final String SHARED = "public, max-age=0, s-maxage=60, stale-while-revalidate=300";

	@Test
	public void anonymousCatalogReadIsShareable() {
		ResponseEntity<String> list = anonymous("/api/v1/products?store=DEFAULT&lang=en&count=5");
		assertEquals(HttpStatus.OK, list.getStatusCode());
		assertEquals(SHARED, list.getHeaders().getCacheControl());

		ResponseEntity<String> store = anonymous("/api/v1/store/DEFAULT");
		assertEquals(HttpStatus.OK, store.getStatusCode());
		assertEquals(SHARED, store.getHeaders().getCacheControl());
	}

	@Test
	public void authenticatedReadIsNotShared() {
		ResponseEntity<String> list = testRestTemplate.exchange("/api/v1/products?store=DEFAULT&lang=en&count=5",
				HttpMethod.GET, new HttpEntity<>(getHeader()), String.class);
		assertEquals(HttpStatus.OK, list.getStatusCode());
		assertTrue(list.getHeaders().getCacheControl(), list.getHeaders().getCacheControl().contains("no-store"));
	}

	@Test
	public void unknownSlugIsNotFoundAndNotShared() {
		ResponseEntity<String> missing = anonymous("/api/v1/product/no-such-product?store=DEFAULT&lang=en");
		assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
		assertTrue(String.valueOf(missing.getHeaders().getCacheControl()),
				missing.getHeaders().getCacheControl().contains("no-store"));

		ResponseEntity<String> missingV2 = anonymous("/api/v2/product/name/no-such-product?store=DEFAULT&lang=en");
		assertEquals(HttpStatus.NOT_FOUND, missingV2.getStatusCode());
	}

	private ResponseEntity<String> anonymous(String url) {
		return testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
	}
}
