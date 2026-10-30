package com.salesmanager.shop.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ObservabilityConfig {

	/** First filter in the chain, so security, CORS and error handling all log with the id. */
	@Bean
	public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.addUrlPatterns("/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
