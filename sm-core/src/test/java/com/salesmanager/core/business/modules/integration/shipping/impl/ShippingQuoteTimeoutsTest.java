package com.salesmanager.core.business.modules.integration.shipping.impl;

import static org.junit.Assert.assertEquals;

import org.apache.http.client.config.RequestConfig;
import org.junit.Test;

public class ShippingQuoteTimeoutsTest {

	@Test
	public void upsClientHasDefaultTimeouts() {
		assertTimeouts(new UPSShippingQuote().requestConfig());
	}

	@Test
	public void uspsClientHasDefaultTimeouts() {
		assertTimeouts(new USPSShippingQuote().requestConfig());
	}

	private void assertTimeouts(RequestConfig config) {
		assertEquals(3000, config.getConnectTimeout());
		assertEquals(3000, config.getConnectionRequestTimeout());
		assertEquals(10000, config.getSocketTimeout());
	}
}
