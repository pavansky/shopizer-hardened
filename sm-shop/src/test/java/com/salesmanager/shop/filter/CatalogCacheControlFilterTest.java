package com.salesmanager.shop.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class CatalogCacheControlFilterTest {

	private final CatalogCacheControlFilter filter = new CatalogCacheControlFilter();

	@Test
	public void anonymousCatalogReadsAreShareable() throws Exception {
		for (String path : new String[] { "/api/v1/products", "/api/v1/products/", "/api/v1/product/oak-bench",
				"/api/v2/product/oak-bench", "/api/v1/category/furniture", "/api/v1/category",
				"/api/v1/store/DEFAULT", "/api/v1/config", "/api/v1/content/pages/about",
				"/api/v1/product/12/reviews" }) {
			assertEquals(path, CatalogCacheControlFilter.PUBLIC_CATALOG, run("GET", path, null, 200));
		}
		assertEquals(CatalogCacheControlFilter.PUBLIC_CATALOG, run("HEAD", "/api/v1/products", null, 200));
	}

	@Test
	public void personalOrAuthenticatedRequestsAreLeftAlone() throws Exception {
		// the filter sets nothing, so Spring Security's no-store default applies
		assertNull(run("GET", "/api/v1/products", "Bearer abc", 200));
		assertNull(run("GET", "/api/v1/private/products", null, 200));
		assertNull(run("GET", "/api/v1/store/DEFAULT/private/x", null, 200));
		assertNull(run("GET", "/api/v1/auth/customer/profile", null, 200));
		assertNull(run("GET", "/api/v1/cart/abc123", null, 200));
		assertNull(run("GET", "/api/v1/customers/4/reviews", null, 200));
		assertNull(run("GET", "/api/v1/orders/1", null, 200));
		assertNull(run("GET", "/api/v1/languages", null, 200));
		assertNull(run("POST", "/api/v1/products", null, 200));
		assertNull(run("POST", "/api/v1/product/3/price", null, 200));
	}

	@Test
	public void errorAnswersAreNotShared() throws Exception {
		assertEquals(CatalogCacheControlFilter.NO_STORE, run("GET", "/api/v1/product/missing", null, -404));
		assertEquals(CatalogCacheControlFilter.NO_STORE, run("GET", "/api/v1/products", null, 503));
	}

	/** status below zero means the handler calls sendError instead of setStatus */
	private String run(String method, String path, String authorization, int status)
			throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain(new HttpServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
				if (status < 0) {
					res.sendError(-status, "not found");
				} else {
					res.setStatus(status);
				}
			}
		});
		filter.doFilter(request, response, chain);
		return response.getHeader("Cache-Control");
	}
}
