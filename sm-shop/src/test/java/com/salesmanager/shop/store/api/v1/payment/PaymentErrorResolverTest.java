package com.salesmanager.shop.store.api.v1.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.payments.PaymentGatewayBusyException;
import com.salesmanager.shop.store.api.exception.ServiceRuntimeException;

class PaymentErrorResolverTest {

	private final PaymentErrorResolver resolver = new PaymentErrorResolver();

	@Test
	void busyGatewayIs503WithRetryAfterEvenWhenWrapped() throws Exception {
		// the checkout facade wraps the service exception, the anonymous checkout wraps it again
		Exception wrapped = new ServiceRuntimeException("Error during checkout",
				new ServiceException(new PaymentGatewayBusyException("stripe3", 2)));
		MockHttpServletResponse response = new MockHttpServletResponse();

		ModelAndView handled = resolver.resolveException(new MockHttpServletRequest(), response, null, wrapped);

		assertNotNull(handled);
		assertTrue(handled.isEmpty());
		assertEquals(503, response.getStatus());
		assertEquals("2", response.getHeader("Retry-After"));
		JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
		assertEquals(PaymentErrorResolver.GATEWAY_BUSY_CODE, body.path("errorCode").asText());
		assertTrue(PaymentErrorResolver.handles(wrapped));
	}

	@Test
	void otherFailuresAreLeftToTheUsualHandlers() {
		Exception other = new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, "declined");
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertNull(resolver.resolveException(new MockHttpServletRequest(), response, null, other));
		assertEquals(200, response.getStatus());
		assertFalse(PaymentErrorResolver.handles(other));
	}

	@Test
	void runsBeforeSpringsExceptionHandlerResolvers() {
		// HandlerExceptionResolverComposite, which holds @ExceptionHandler and the
		// RestErrorHandler advice, is registered with order 0
		assertTrue(resolver.getOrder() < 0);
	}
}
