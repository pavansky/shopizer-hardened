package com.salesmanager.shop.store.api.v1.payment;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.modules.integration.payment.impl.PaymentActionRequiredException;
import com.salesmanager.core.business.services.payments.PaymentGatewayBusyException;

/**
 * Turns payment outcomes that are not errors into their HTTP answer, wherever
 * the checkout or payment code wrapped them:
 *
 * <ul>
 * <li>{@link PaymentActionRequiredException}: 409 payment_action_required with the
 * PaymentIntent client secret. Nothing was charged; the order stays open while the
 * customer completes 3-D Secure, then the client retries with the same
 * Idempotency-Key.</li>
 * <li>{@link PaymentGatewayBusyException}: 503 with Retry-After. The gateway was
 * never called, so retrying with the same Idempotency-Key is safe.</li>
 * </ul>
 *
 * A resolver rather than an @ExceptionHandler: RestErrorHandler already handles
 * Exception at the highest advice precedence, so an advice could not reliably
 * come first. This resolver runs before Spring's exception handler resolvers and
 * answers null for anything else, which then follows the usual path.
 */
@Component
public class PaymentErrorResolver implements HandlerExceptionResolver, Ordered {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentErrorResolver.class);

	public static final String ACTION_REQUIRED_CODE = "payment_action_required";
	public static final String GATEWAY_BUSY_CODE = "payment_gateway_busy";

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	public int getOrder() {
		// after Spring Boot's DefaultErrorAttributes (which only records the error),
		// before the HandlerExceptionResolverComposite (order 0)
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

	/** True when the failure is one this resolver answers; callers that swallow errors rethrow these. */
	public static boolean handles(Throwable e) {
		return find(e, PaymentActionRequiredException.class) != null
				|| find(e, PaymentGatewayBusyException.class) != null;
	}

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		PaymentActionRequiredException action = find(ex, PaymentActionRequiredException.class);
		if (action != null) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("errorCode", ACTION_REQUIRED_CODE);
			body.put("message", "The payment is not complete yet: confirm it with your bank, then submit again.");
			body.put("paymentIntentId", action.getPaymentIntentId());
			body.put("paymentIntentStatus", action.getStatus());
			body.put("clientSecret", action.getClientSecret());
			body.put("orderId", action.getOrderId());
			return write(response, HttpServletResponse.SC_CONFLICT, body);
		}
		PaymentGatewayBusyException busy = find(ex, PaymentGatewayBusyException.class);
		if (busy == null) {
			return null;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("errorCode", GATEWAY_BUSY_CODE);
		body.put("message", "Payment is temporarily unavailable; nothing was charged. Please retry.");
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(busy.getRetryAfterSeconds()));
		return write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, body);
	}

	private ModelAndView write(HttpServletResponse response, int status, Map<String, Object> body) {
		try {
			response.setStatus(status);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			mapper.writeValue(response.getWriter(), body);
		} catch (IOException e) {
			LOGGER.warn("Could not write payment error response", e);
		}
		return new ModelAndView();
	}

	static <T extends Throwable> T find(Throwable e, Class<T> type) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (type.isInstance(t)) {
				return type.cast(t);
			}
		}
		return null;
	}
}
