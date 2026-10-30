package com.salesmanager.shop.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import javax.servlet.DispatcherType;

import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	@Test
	public void keepsASafeIncomingIdInMdcAndResponse() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/store/DEFAULT");
		request.addHeader(RequestIdFilter.HEADER, "lb-7f3a.42");
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] seenInChain = new String[1];

		filter.doFilter(request, response, new MockFilterChain() {
			@Override
			public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) {
				seenInChain[0] = MDC.get(RequestIdFilter.MDC_KEY);
			}
		});

		assertEquals("lb-7f3a.42", seenInChain[0]);
		assertEquals("lb-7f3a.42", response.getHeader(RequestIdFilter.HEADER));
		assertNull("the id must not leak to the next request on this thread", MDC.get(RequestIdFilter.MDC_KEY));
	}

	@Test
	public void createsAnIdWhenNoneIsSent() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest("GET", "/"), response, new MockFilterChain());

		String id = response.getHeader(RequestIdFilter.HEADER);
		assertEquals(id, UUID.fromString(id).toString());
	}

	@Test
	public void replacesUnsafeOrOversizedIds() {
		String forged = "abc\n2026-10-30 ERROR fake log line";
		assertNotEquals(forged, RequestIdFilter.resolve(forged));
		String tooLong = new String(new char[65]).replace('\0', 'a');
		assertNotEquals(tooLong, RequestIdFilter.resolve(tooLong));
		assertTrue(RequestIdFilter.resolve("").length() > 30);
		assertNotEquals("a@b.com", RequestIdFilter.resolve("a@b.com"));
	}

	@Test
	public void clearsMdcWhenTheChainThrows() throws Exception {
		try {
			filter.doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), new MockFilterChain() {
				@Override
				public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) {
					throw new IllegalStateException("boom");
				}
			});
		} catch (IllegalStateException expected) {
			// rethrown as is
		}
		assertNull(MDC.get(RequestIdFilter.MDC_KEY));
	}

	@Test
	public void errorDispatchKeepsTheIdOfTheFailedRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		String id = response.getHeader(RequestIdFilter.HEADER);

		// the container then forwards the same request to /error
		request.setDispatcherType(DispatcherType.ERROR);
		MockHttpServletResponse errorResponse = new MockHttpServletResponse();
		String[] seenInChain = new String[1];
		filter.doFilter(request, errorResponse, new MockFilterChain() {
			@Override
			public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) {
				seenInChain[0] = MDC.get(RequestIdFilter.MDC_KEY);
			}
		});

		assertEquals("error page log lines carry the request's id", id, seenInChain[0]);
		assertEquals(id, errorResponse.getHeader(RequestIdFilter.HEADER));
		assertNull(MDC.get(RequestIdFilter.MDC_KEY));
	}
}
