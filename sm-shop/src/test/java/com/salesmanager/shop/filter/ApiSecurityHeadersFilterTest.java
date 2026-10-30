package com.salesmanager.shop.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.salesmanager.shop.store.security.abuse.ClientIpResolver;

class ApiSecurityHeadersFilterTest {

	private MockHttpServletResponse run(ApiSecurityHeadersFilter filter, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	@Test
	void apiResponsesForbidScriptsFramingAndSniffing() throws Exception {
		MockHttpServletResponse res = run(new ApiSecurityHeadersFilter(new ClientIpResolver("")),
				new MockHttpServletRequest("GET", "/api/v1/products"));
		assertEquals("default-src 'none'; frame-ancestors 'none'", res.getHeader("Content-Security-Policy"));
		assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
		assertEquals("no-referrer", res.getHeader("Referrer-Policy"));
		assertNull(res.getHeader("Strict-Transport-Security"), "no HSTS over plain HTTP");
	}

	@Test
	void hstsOnHttps() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
		req.setSecure(true);
		MockHttpServletResponse res = run(new ApiSecurityHeadersFilter(new ClientIpResolver("")), req);
		assertEquals("max-age=31536000; includeSubDomains", res.getHeader("Strict-Transport-Security"));
	}

	@Test
	void forwardedProtoCountsOnlyFromATrustedProxy() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
		req.setRemoteAddr("10.0.0.5");
		req.addHeader("X-Forwarded-Proto", "https");
		assertNull(run(new ApiSecurityHeadersFilter(new ClientIpResolver("")), req)
				.getHeader("Strict-Transport-Security"));
		assertEquals(ApiSecurityHeadersFilter.HSTS,
				run(new ApiSecurityHeadersFilter(new ClientIpResolver("10.0.0.0/8")), req)
						.getHeader("Strict-Transport-Security"));
	}
}
