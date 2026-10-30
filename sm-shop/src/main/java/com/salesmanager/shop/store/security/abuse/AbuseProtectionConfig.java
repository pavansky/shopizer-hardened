package com.salesmanager.shop.store.security.abuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Rate limits and login lockout. Every value is a property with a default:
 *
 * <pre>
 * shopizer.ratelimit.enabled=true
 * shopizer.ratelimit.&lt;rule&gt;.capacity / .period-seconds   per client address
 *   login 10/60, register 5/60, password-reset 5/60, password-change 5/60,
 *   checkout 20/60, payment-init 20/60
 *   (the login budget also covers every request that carries HTTP Basic
 *   credentials on the /api/v* /private, /api/v* /auth, /services and /shop chains)
 * shopizer.login.lockout.max-failures=5
 * shopizer.login.lockout.window-seconds=900
 * shopizer.login.lockout.lock-seconds=300
 * shopizer.security.trusted-proxies=   (empty: X-Forwarded-For is never read)
 * </pre>
 */
@Configuration
public class AbuseProtectionConfig {

	/** Runs after the Spring Security chain (order -100), which has already set the CORS headers. */
	static final int FILTER_ORDER = 0;

	@Bean
	public ClientIpResolver clientIpResolver(@Value("${shopizer.security.trusted-proxies:}") String trustedProxies) {
		return new ClientIpResolver(trustedProxies);
	}

	@Bean
	public LoginAttemptTracker loginAttemptTracker(
			@Value("${shopizer.login.lockout.max-failures:5}") int maxFailures,
			@Value("${shopizer.login.lockout.window-seconds:900}") long windowSeconds,
			@Value("${shopizer.login.lockout.lock-seconds:300}") long lockSeconds) {
		return new LoginAttemptTracker(maxFailures, windowSeconds, lockSeconds);
	}

	/**
	 * The login budget per client address, shared by the login endpoints and by
	 * HTTP Basic authentication inside the security chains, so neither can be
	 * used to get around the other.
	 */
	@Bean
	public TokenBucketLimiter loginRateLimiter(Environment env) {
		return limiter(env, "login", 10);
	}

	/**
	 * Filter placed in a security chain before BasicAuthenticationFilter: Basic
	 * credentials are checked there, before the servlet-level rateLimitFilter
	 * runs. Not a bean, so Spring Boot does not register it a second time.
	 */
	public static RateLimitFilter basicAuthRateLimitFilter(TokenBucketLimiter loginRateLimiter,
			ClientIpResolver clientIpResolver) {
		return new RateLimitFilter(
				Collections.singletonList(RateLimitFilter.Rule.basicAuth("login", loginRateLimiter, "/**")),
				clientIpResolver);
	}

	@Bean
	public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(Environment env, ClientIpResolver clientIpResolver,
			TokenBucketLimiter loginRateLimiter) {
		String v = "/api/v*";
		List<RateLimitFilter.Rule> rules = new ArrayList<>();
		rules.add(new RateLimitFilter.Rule("login", "POST", loginRateLimiter, v + "/private/login", v + "/customer/login"));
		rules.add(rule(env, "register", 5, v + "/customer/register", v + "/store/signup"));
		rules.add(rule(env, "password-reset", 5,
				v + "/customer/password/reset/request", v + "/user/password/reset/request",
				v + "/customer/*/password/*", v + "/user/*/password/*"));
		// the current password is checked here: without a limit it is an oracle
		rules.add(new RateLimitFilter.Rule("password-change", "PATCH", limiter(env, "password-change", 5),
				v + "/private/user/*/password"));
		rules.add(rule(env, "checkout", 20, v + "/cart/*/checkout", v + "/auth/cart/*/checkout"));
		rules.add(rule(env, "payment-init", 20, v + "/cart/*/payment/init", v + "/auth/cart/*/payment/init"));

		FilterRegistrationBean<RateLimitFilter> registration =
				new FilterRegistrationBean<>(new RateLimitFilter(rules, clientIpResolver));
		registration.setName("rateLimitFilter");
		registration.addUrlPatterns("/api/*");
		registration.setOrder(FILTER_ORDER);
		registration.setEnabled(env.getProperty("shopizer.ratelimit.enabled", Boolean.class, Boolean.TRUE));
		return registration;
	}

	private static RateLimitFilter.Rule rule(Environment env, String name, long defaultCapacity, String... patterns) {
		return new RateLimitFilter.Rule(name, "POST", limiter(env, name, defaultCapacity), patterns);
	}

	private static TokenBucketLimiter limiter(Environment env, String name, long defaultCapacity) {
		long capacity = env.getProperty("shopizer.ratelimit." + name + ".capacity", Long.class, defaultCapacity);
		long period = env.getProperty("shopizer.ratelimit." + name + ".period-seconds", Long.class, 60L);
		return new TokenBucketLimiter(capacity, period);
	}
}
