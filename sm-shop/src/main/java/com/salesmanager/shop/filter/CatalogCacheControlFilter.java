package com.salesmanager.shop.filter;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a CDN or shared cache keep anonymous catalog reads for a short time.
 *
 * Spring Security writes "Cache-Control: no-cache, no-store" on every response unless
 * the header is already set, so public product, category, store, config and content
 * reads could never be cached, although they are most of the traffic. For an anonymous
 * GET or HEAD on those paths this filter sets
 * {@value #PUBLIC_CATALOG} before the request is handled: browsers revalidate every time
 * (max-age=0), shared caches keep the response for 60 s and may serve it stale for 5
 * minutes while they refetch it.
 *
 * Anything else keeps the no-store default: requests with an Authorization header,
 * /private and /auth paths, cart, checkout, customer and order endpoints, and every
 * response with an error status.
 *
 * Product responses still carry the exact stock quantity, so a cached page can show a
 * count up to a minute old. Checkout checks stock again, so this only affects display.
 */
@Component
public class CatalogCacheControlFilter extends OncePerRequestFilter {

	static final String PUBLIC_CATALOG = "public, max-age=0, s-maxage=60, stale-while-revalidate=300";
	static final String NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";

	/** public catalog reads: /api/v1|v2/products, /product, /category, /store, /config, /content */
	private static final Pattern CATALOG_READ = Pattern
			.compile("^/api/v[12]/(products?|category|categories|store|config|content)(/.*)?$");

	/** never shared, even under a catalog prefix */
	private static final Pattern PERSONAL = Pattern
			.compile("/(private|auth|cart|carts|checkout|customer|customers|order|orders)(/|$)");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!isPublicCatalogRead(request)) {
			chain.doFilter(request, response);
			return;
		}
		response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_CATALOG);
		chain.doFilter(request, new ErrorsAreNotShared(response));
	}

	static boolean isPublicCatalogRead(HttpServletRequest request) {
		String method = request.getMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			return false;
		}
		if (!StringUtils.isBlank(request.getHeader(HttpHeaders.AUTHORIZATION))) {
			return false;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return CATALOG_READ.matcher(path).matches() && !PERSONAL.matcher(path).find();
	}

	/** an error answer (404 for an unknown slug, 500, 503) must not be cached by a CDN */
	private static final class ErrorsAreNotShared extends HttpServletResponseWrapper {

		ErrorsAreNotShared(HttpServletResponse response) {
			super(response);
		}

		private void check(int status) {
			if (status >= 400 && !isCommitted()) {
				setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
			}
		}

		@Override
		public void setStatus(int sc) {
			check(sc);
			super.setStatus(sc);
		}

		@Override
		public void sendError(int sc) throws IOException {
			check(sc);
			super.sendError(sc);
		}

		@Override
		public void sendError(int sc, String msg) throws IOException {
			check(sc);
			super.sendError(sc, msg);
		}
	}
}
