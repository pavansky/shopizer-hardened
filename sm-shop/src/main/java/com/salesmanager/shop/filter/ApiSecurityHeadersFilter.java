package com.salesmanager.shop.filter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.salesmanager.shop.store.security.abuse.ClientIpResolver;

/**
 * Browser hardening headers for API responses. The API returns JSON, never a
 * page, so its Content-Security-Policy allows nothing: a response opened
 * directly in a browser cannot run script or be framed. HSTS is sent only on
 * HTTPS, directly or through a trusted proxy that reports X-Forwarded-Proto.
 * Headers are set before the request is handled, so error responses carry them.
 */
public class ApiSecurityHeadersFilter extends OncePerRequestFilter {

	static final String CSP = "default-src 'none'; frame-ancestors 'none'";
	static final String REFERRER_POLICY = "no-referrer";
	static final String HSTS = "max-age=31536000; includeSubDomains";

	private final ClientIpResolver clientIpResolver;

	public ApiSecurityHeadersFilter(ClientIpResolver clientIpResolver) {
		this.clientIpResolver = clientIpResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Content-Security-Policy", CSP);
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", REFERRER_POLICY);
		if (request.isSecure() || clientIpResolver.isForwardedHttps(request)) {
			response.setHeader("Strict-Transport-Security", HSTS);
		}
		chain.doFilter(request, response);
	}
}
