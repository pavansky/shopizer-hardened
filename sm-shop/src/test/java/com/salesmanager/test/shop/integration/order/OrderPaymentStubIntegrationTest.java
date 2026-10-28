package com.salesmanager.test.shop.integration.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.order.v0.ReadableOrderList;
import com.salesmanager.shop.store.api.exception.ErrorEntity;
import com.salesmanager.test.shop.common.ServicesTestSupport;

@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class OrderPaymentStubIntegrationTest extends ServicesTestSupport {

    @Test
    public void captureReturnsNotImplemented() throws Exception {
        assertNotImplemented("/api/v1/private/orders/1/capture");
    }

    @Test
    public void refundReturnsNotImplemented() throws Exception {
        assertNotImplemented("/api/v1/private/orders/1/refund");
    }

    @Test
    public void capturableReturnsOkWithBody() throws Exception {
        final HttpEntity<String> httpEntity = new HttpEntity<>(getHeader());
        final ResponseEntity<ReadableOrderList> response = testRestTemplate.exchange(
                "/api/v1/private/orders/payment/capturable", HttpMethod.GET, httpEntity, ReadableOrderList.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    private void assertNotImplemented(String url) {
        final HttpEntity<String> httpEntity = new HttpEntity<>(getHeader());
        final ResponseEntity<ErrorEntity> response = testRestTemplate.postForEntity(url, httpEntity, ErrorEntity.class);

        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("501", response.getBody().getErrorCode());
    }

}
