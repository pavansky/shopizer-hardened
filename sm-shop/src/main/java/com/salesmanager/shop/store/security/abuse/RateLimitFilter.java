package com.salesmanager.shop.store.security.abuse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Per client address rate limits on the endpoints that attackers automate:
 * login, registration, password reset, checkout and payment initialisation.
 * A request over the limit gets 429 with a Retry-After header and never
 * reaches the controller.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

	static final String BODY = "{\"message\":\"Too many requests\"}";

	/** A named group of endpoints sharing one limit. */
	public static final class Rule {
		final String name;
		final String method;
		final List<String> patterns;
		final TokenBucketLimiter limiter;

		public Rule(String name, String method, TokenBucketLimiter limiter, String... patterns) {
			this.name = name;
			this.method = method;
			this.limiter = limiter;
			this.patterns = Collections.unmodifiableList(Arrays.asList(patterns));
		}
	}

	private final List<Rule> rules;
	private final ClientIpResolver ipResolver;
	private final AntPathMatcher matcher = new AntPathMatcher();
	private final UrlPathHelper pathHelper = new UrlPathHelper();

	public RateLimitFilter(List<Rule> rules, ClientIpResolver ipResolver) {
		this.rules = rules;
		this.ipResolver = ipResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Rule rule = match(request);
		if (rule != null) {
			String client = ipResolver.resolve(request);
			long retryAfter = rule.limiter.tryConsume(rule.name + "|" + client);
			if (retryAfter > 0) {
				LOGGER.warn("rate limit {} exceeded for {}", rule.name, client);
				response.setStatus(429);
				response.setHeader("Retry-After", Long.toString(retryAfter));
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.setCharacterEncoding(StandardCharsets.UTF_8.name());
				response.getWriter().write(BODY);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	Rule match(HttpServletRequest request) {
		// decoded path within the application, the same path Spring MVC maps
		String path = pathHelper.getPathWithinApplication(request);
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		for (Rule rule : rules) {
			if (!rule.method.equalsIgnoreCase(request.getMethod())) {
				continue;
			}
			for (String pattern : rule.patterns) {
				if (matcher.match(pattern, path)) {
					return rule;
				}
			}
		}
		return null;
	}
}
