package com.salesmanager.shop.store.security.audit;

import java.io.IOException;
import java.util.Date;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.salesmanager.core.business.repositories.system.AdminAuditEventRepository;
import com.salesmanager.core.model.system.AdminAuditEvent;
import com.salesmanager.shop.store.security.abuse.ClientIpResolver;

/**
 * Writes one ADMIN_AUDIT row for every POST, PUT, PATCH or DELETE that reaches
 * the admin API with an authenticated principal, including requests refused
 * with 403. It sits in the admin security chain before the exception
 * translation filter, so the principal is still known and the final status
 * (2xx, 4xx or 403) is set when the row is written. Bodies are never read.
 * A failure to write the row is logged and does not fail the request.
 */
public class AdminAuditFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditFilter.class);

	static final String DEFAULT_STORE = "DEFAULT";

	private final AdminAuditEventRepository repository;
	private final ClientIpResolver clientIpResolver;

	public AdminAuditFilter(AdminAuditEventRepository repository, ClientIpResolver clientIpResolver) {
		this.repository = repository;
		this.clientIpResolver = clientIpResolver;
	}

	static boolean isMutating(String method) {
		return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !isMutating(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String username = username();
		boolean failed = true;
		try {
			chain.doFilter(request, response);
			failed = false;
		} finally {
			// the login endpoint authenticates inside the controller
			if (username == null) {
				username = username();
			}
			if (username != null) {
				record(request, failed ? 500 : response.getStatus(), username);
			}
		}
	}

	private static String username() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return auth.getName();
	}

	private void record(HttpServletRequest request, int status, String username) {
		try {
			String store = StringUtils.defaultIfBlank(request.getParameter("store"), DEFAULT_STORE);
			repository.save(new AdminAuditEvent(new Date(), username, store, request.getMethod(),
					request.getRequestURI(), status, clientIpResolver.resolve(request)));
		} catch (RuntimeException e) {
			LOGGER.error("could not write admin audit row for {} {}", request.getMethod(), request.getRequestURI(), e);
		}
	}
}
