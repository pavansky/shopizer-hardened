package com.salesmanager.shop.store.security.abuse;

import java.util.ArrayList;
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
 *   login 10/60, register 5/60, password-reset 5/60, checkout 20/60, payment-init 20/60
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

	@Bean
	public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(Environment env, ClientIpResolver clientIpResolver) {
		String v = "/api/v*";
		List<RateLimitFilter.Rule> rules = new ArrayList<>();
		rules.add(rule(env, "login", 10, v + "/private/login", v + "/customer/login"));
		rules.add(rule(env, "register", 5, v + "/customer/register", v + "/store/signup"));
		rules.add(rule(env, "password-reset", 5,
				v + "/customer/password/reset/request", v + "/user/password/reset/request",
				v + "/customer/*/password/*", v + "/user/*/password/*"));
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
		long capacity = env.getProperty("shopizer.ratelimit." + name + ".capacity", Long.class, defaultCapacity);
		long period = env.getProperty("shopizer.ratelimit." + name + ".period-seconds", Long.class, 60L);
		return new RateLimitFilter.Rule(name, "POST", new TokenBucketLimiter(capacity, period), patterns);
	}
}
