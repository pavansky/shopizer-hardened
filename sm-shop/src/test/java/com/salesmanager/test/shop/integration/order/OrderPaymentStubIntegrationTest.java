package com.salesmanager.test.shop.integration.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.salesmanager.shop.application.ShopApplication;
import com.salesmanager.shop.model.order.v0.ReadableOrderList;
import com.salesmanager.shop.store.api.exception.ErrorEntity;
import com.salesmanager.test.shop.common.ServicesTestSupport;

/**
 * Capture and refund endpoints over HTTP. State-dependent outcomes (409, 400,
 * status transitions) are covered by OrderFacadePaymentTest and PaymentServiceRefundTest.
 */
@SpringBootTest(classes = ShopApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@RunWith(SpringRunner.class)
public class OrderPaymentStubIntegrationTest extends ServicesTestSupport {

    private static final long UNKNOWN_ORDER = 987654321L;

    @Test
    public void captureUnknownOrderReturnsNotFound() throws Exception {
        assertNotFound("/api/v1/private/orders/" + UNKNOWN_ORDER + "/capture", new HttpEntity<>(getHeader()));
    }

    @Test
    public void refundUnknownOrderReturnsNotFound() throws Exception {
        assertNotFound("/api/v1/private/orders/" + UNKNOWN_ORDER + "/refund", new HttpEntity<>(getHeader()));
    }

    @Test
    public void refundUnknownOrderWithAmountReturnsNotFound() throws Exception {
        final HttpHeaders headers = getHeader();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertNotFound("/api/v1/private/orders/" + UNKNOWN_ORDER + "/refund",
                new HttpEntity<>("{\"amount\": 1.00}", headers));
    }

    @Test
    public void capturableReturnsOkWithBody() throws Exception {
        final HttpEntity<String> httpEntity = new HttpEntity<>(getHeader());
        final ResponseEntity<ReadableOrderList> response = testRestTemplate.exchange(
                "/api/v1/private/orders/payment/capturable", HttpMethod.GET, httpEntity, ReadableOrderList.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    private void assertNotFound(String url, HttpEntity<String> httpEntity) {
        final ResponseEntity<ErrorEntity> response = testRestTemplate.postForEntity(url, httpEntity, ErrorEntity.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
    }

}
