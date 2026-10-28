package com.salesmanager.shop.utils;

import static org.junit.Assert.assertEquals;

import org.apache.http.client.config.RequestConfig;
import org.junit.Test;

public class CaptchaRequestUtilsTimeoutTest {

	@Test
	public void requestConfigCarriesTimeouts() {
		RequestConfig config = CaptchaRequestUtils.requestConfig(3000, 10000);
		assertEquals(3000, config.getConnectTimeout());
		assertEquals(3000, config.getConnectionRequestTimeout());
		assertEquals(10000, config.getSocketTimeout());
	}
}
