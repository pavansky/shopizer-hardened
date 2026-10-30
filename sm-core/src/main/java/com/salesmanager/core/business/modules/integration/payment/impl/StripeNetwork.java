package com.salesmanager.core.business.modules.integration.payment.impl;

import org.apache.commons.lang3.StringUtils;

import com.stripe.net.RequestOptions;

/**
 * Network settings shared by the Stripe modules. A connect or read timeout ends
 * in ApiConnectionException, which the modules report as an unknown outcome:
 * the order stays PAYMENT_PENDING and the webhook or the reconciler settles it.
 * Network retries are safe because the SDK sends the same idempotency key on
 * every retry of one call.
 */
final class StripeNetwork {

	static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
	static final int DEFAULT_READ_TIMEOUT_MS = 15000;
	static final int DEFAULT_MAX_NETWORK_RETRIES = 2;

	private StripeNetwork() {
	}

	static RequestOptions requestOptions(String apiKey, String idempotencyKey, int connectTimeoutMs,
			int readTimeoutMs, int maxNetworkRetries) {
		RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
				.setApiKey(apiKey)
				.setConnectTimeout(connectTimeoutMs)
				.setReadTimeout(readTimeoutMs)
				.setMaxNetworkRetries(maxNetworkRetries);
		if (StringUtils.isNotBlank(idempotencyKey)) {
			builder.setIdempotencyKey(idempotencyKey);
		}
		return builder.build();
	}
}
