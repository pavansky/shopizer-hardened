package com.salesmanager.shop.application.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.salesmanager.shop.filter.ApiSecurityHeadersFilter;
import com.salesmanager.shop.store.security.abuse.ClientIpResolver;

/**
 * Registers the API security headers. Only API paths: Swagger UI and the
 * other HTML pages need scripts and keep their own headers.
 */
@Configuration
public class SecurityHeadersConfig {

	@Bean
	public FilterRegistrationBean<ApiSecurityHeadersFilter> apiSecurityHeadersFilter(ClientIpResolver clientIpResolver) {
		FilterRegistrationBean<ApiSecurityHeadersFilter> registration =
				new FilterRegistrationBean<>(new ApiSecurityHeadersFilter(clientIpResolver));
		registration.setName("apiSecurityHeadersFilter");
		registration.addUrlPatterns("/api/*", "/services/*", "/actuator/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}
}
