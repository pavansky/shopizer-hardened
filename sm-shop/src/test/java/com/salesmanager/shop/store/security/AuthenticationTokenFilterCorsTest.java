package com.salesmanager.shop.store.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** CORS headers are only emitted for allow-listed origins. */
class AuthenticationTokenFilterCorsTest {

	private MockHttpServletResponse run(String origin) throws Exception {
		AuthenticationTokenFilter filter = new AuthenticationTokenFilter();
		filter.setAllowedOriginsProperty("http://localhost:3000, http://localhost:4200");
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
		if (origin != null) {
			req.addHeader("Origin", origin);
		}
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(req, res, new MockFilterChain());
		return res;
	}

	@Test
	void allowedOriginIsEchoed() throws Exception {
		MockHttpServletResponse res = run("http://localhost:4200");
		assertEquals("http://localhost:4200", res.getHeader("Access-Control-Allow-Origin"));
		assertEquals("true", res.getHeader("Access-Control-Allow-Credentials"));
	}

	@Test
	void unknownOriginGetsNoCorsHeaders() throws Exception {
		MockHttpServletResponse res = run("https://evil.example");
		assertNull(res.getHeader("Access-Control-Allow-Origin"));
		assertNull(res.getHeader("Access-Control-Allow-Credentials"));
	}

	@Test
	void parsesCommaSeparatedList() {
		assertTrue(AuthenticationTokenFilter.parseOrigins(" a , b ,").contains("b"));
		assertFalse(AuthenticationTokenFilter.parseOrigins("").contains(""));
	}
}
