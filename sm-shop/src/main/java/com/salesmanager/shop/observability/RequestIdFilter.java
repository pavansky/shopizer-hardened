package com.salesmanager.shop.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id that appears in each log line written while serving it
 * (MDC key {@value #MDC_KEY}) and in the X-Request-Id response header, so a shopper,
 * a merchant or a load balancer log can quote it to support.
 *
 * An incoming X-Request-Id is kept when it is short and made of safe characters, so an
 * id set by a proxy follows the request through. Anything else is replaced by a new
 * UUID: the value goes into log lines, and free text there would allow log injection.
 *
 * The filter also runs on the container's ERROR dispatch (the /error page after an
 * uncaught exception), which comes after the first pass has cleared the MDC. The id is
 * kept in a request attribute so the error dispatch logs and returns the same id.
 */
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Object earlier = request.getAttribute(ATTRIBUTE);
		String requestId = earlier instanceof String ? (String) earlier : resolve(request.getHeader(HEADER));
		request.setAttribute(ATTRIBUTE, requestId);
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	static String resolve(String incoming) {
		if (incoming != null && SAFE.matcher(incoming).matches()) {
			return incoming;
		}
		return UUID.randomUUID().toString();
	}

}
