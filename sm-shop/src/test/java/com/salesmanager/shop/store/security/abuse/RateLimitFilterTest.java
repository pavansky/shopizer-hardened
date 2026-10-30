package com.salesmanager.shop.store.security.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 429 with Retry-After once a client exceeds the limit of a rule. */
class RateLimitFilterTest {

	private final AtomicLong nanos = new AtomicLong();
	private RateLimitFilter filter;

	@BeforeEach
	void setUp() {
		TokenBucketLimiter login = new TokenBucketLimiter(3, 60, nanos::get);
		TokenBucketLimiter checkout = new TokenBucketLimiter(1, 60, nanos::get);
		filter = new RateLimitFilter(Arrays.asList(
				new RateLimitFilter.Rule("login", "POST", login, "/api/v*/private/login", "/api/v*/customer/login"),
				new RateLimitFilter.Rule("checkout", "POST", checkout, "/api/v*/cart/*/checkout")),
				new ClientIpResolver(""));
	}

	private MockHttpServletResponse call(String method, String uri, String ip) throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
		req.setRemoteAddr(ip);
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(req, res, new MockFilterChain());
		return res;
	}

	@Test
	void fourthLoginInAMinuteGets429WithRetryAfter() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertEquals(200, call("POST", "/api/v1/private/login", "10.0.0.1").getStatus());
		}
		MockHttpServletResponse limited = call("POST", "/api/v1/private/login", "10.0.0.1");
		assertEquals(429, limited.getStatus());
		assertEquals("20", limited.getHeader("Retry-After"));
		assertEquals(RateLimitFilter.BODY, limited.getContentAsString());
	}

	@Test
	void adminAndCustomerLoginShareTheLoginBudget() throws Exception {
		call("POST", "/api/v1/private/login", "10.0.0.1");
		call("POST", "/api/v1/customer/login", "10.0.0.1");
		call("POST", "/api/v1/private/login", "10.0.0.1");
		assertEquals(429, call("POST", "/api/v1/customer/login", "10.0.0.1").getStatus());
	}

	@Test
	void otherClientsAndOtherRulesAreNotAffected() throws Exception {
		for (int i = 0; i < 4; i++) {
			call("POST", "/api/v1/private/login", "10.0.0.1");
		}
		assertEquals(200, call("POST", "/api/v1/private/login", "10.0.0.2").getStatus());
		assertEquals(200, call("POST", "/api/v1/cart/abc/checkout", "10.0.0.1").getStatus());
		assertEquals(429, call("POST", "/api/v1/cart/abc/checkout", "10.0.0.1").getStatus());
	}

	@Test
	void trailingSlashAndEncodedPathsCannotBypassTheLimit() throws Exception {
		call("POST", "/api/v1/private/login", "10.0.0.1");
		call("POST", "/api/v1/private/login/", "10.0.0.1");
		call("POST", "/api/v1/private/%6Cogin", "10.0.0.1");
		assertEquals(429, call("POST", "/api/v1/private/login", "10.0.0.1").getStatus());
	}

	@Test
	void tokensRefill() throws Exception {
		for (int i = 0; i < 3; i++) {
			call("POST", "/api/v1/private/login", "10.0.0.1");
		}
		assertEquals(429, call("POST", "/api/v1/private/login", "10.0.0.1").getStatus());
		nanos.addAndGet(20_000_000_000L);
		assertEquals(200, call("POST", "/api/v1/private/login", "10.0.0.1").getStatus());
	}

	@Test
	void unlistedEndpointsAndMethodsPassThrough() throws Exception {
		for (int i = 0; i < 10; i++) {
			assertEquals(200, call("GET", "/api/v1/private/login", "10.0.0.1").getStatus());
			assertEquals(200, call("POST", "/api/v1/cart", "10.0.0.1").getStatus());
		}
	}

	@Test
	void forwardedForIsIgnoredUnlessThePeerIsATrustedProxy() throws Exception {
		ClientIpResolver none = new ClientIpResolver("");
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/private/login");
		req.setRemoteAddr("203.0.113.9");
		req.addHeader("X-Forwarded-For", "1.2.3.4");
		assertEquals("203.0.113.9", none.resolve(req));

		ClientIpResolver lb = new ClientIpResolver("10.0.0.0/8, 127.0.0.1");
		assertEquals("203.0.113.9", lb.resolve(req), "untrusted peer: header ignored");

		MockHttpServletRequest viaLb = new MockHttpServletRequest("POST", "/api/v1/private/login");
		viaLb.setRemoteAddr("10.1.2.3");
		// a client can prepend anything; the rightmost untrusted hop is the one the proxy saw
		viaLb.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7, 10.9.9.9");
		assertEquals("198.51.100.7", lb.resolve(viaLb));

		MockHttpServletRequest garbage = new MockHttpServletRequest("POST", "/api/v1/private/login");
		garbage.setRemoteAddr("10.1.2.3");
		garbage.addHeader("X-Forwarded-For", "evil.example.com");
		assertEquals("10.1.2.3", lb.resolve(garbage));
	}

	@Test
	void trustedProxyListRejectsHostNames() {
		try {
			new ClientIpResolver("proxy.internal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("proxy.internal"));
			return;
		}
		throw new AssertionError("host names must be refused");
	}

	@Test
	void limiterBoundsTrackedKeys() {
		TokenBucketLimiter limiter = new TokenBucketLimiter(1, 60, nanos::get);
		for (int i = 0; i < TokenBucketLimiter.MAX_KEYS; i++) {
			limiter.tryConsume("k" + i);
		}
		nanos.addAndGet(61_000_000_000L);
		assertEquals(0, limiter.tryConsume("new"));
		assertTrue(limiter.trackedKeys() < 10);
	}
}
