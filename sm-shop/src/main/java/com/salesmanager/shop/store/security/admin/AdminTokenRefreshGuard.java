package com.salesmanager.shop.store.security.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import com.salesmanager.shop.store.security.JWTTokenUtil;

/**
 * Lets GET /api/v* /auth/refresh (the admin token refresh) through only when the
 * presented token would still be accepted by the admin API right now: a valid
 * admin token, not expired, issued after the last password change, for an
 * active user.
 *
 * The refresh handler itself only checks the signature and issues a new token
 * with a fresh issue time. Without this check a token stolen before a password
 * change, or held by a deactivated admin, could be exchanged for a new one that
 * passes every later check. The admin UI calls refresh on every 401, so a
 * revoked session would otherwise resume on its own.
 *
 * A refused refresh gets 401 with a JSON body and no WWW-Authenticate header,
 * so the browser does not open a Basic login dialog and the admin UI logs out.
 */
public class AdminTokenRefreshGuard extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(AdminTokenRefreshGuard.class);

	static final String PATTERN = "/api/v*/auth/refresh";
	static final String BODY = "{\"message\":\"Unauthorized\"}";
	private static final String BEARER = "Bearer ";

	private final JWTTokenUtil jwtTokenUtil;
	private final UserDetailsService adminDetailsService;
	private final String tokenHeader;
	private final AntPathMatcher matcher = new AntPathMatcher();
	private final UrlPathHelper pathHelper = new UrlPathHelper();

	public AdminTokenRefreshGuard(JWTTokenUtil jwtTokenUtil, UserDetailsService adminDetailsService, String tokenHeader) {
		this.jwtTokenUtil = jwtTokenUtil;
		this.adminDetailsService = adminDetailsService;
		this.tokenHeader = StringUtils.defaultIfBlank(tokenHeader, "Authorization");
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"GET".equals(request.getMethod())
				|| !matcher.match(PATTERN, pathHelper.getPathWithinApplication(request));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!stillValid(request.getHeader(tokenHeader))) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write(BODY);
			return;
		}
		chain.doFilter(request, response);
	}

	boolean stillValid(String header) {
		if (header == null || !header.startsWith(BEARER)) {
			return false;
		}
		String token = header.substring(BEARER.length()).trim();
		try {
			String username = jwtTokenUtil.getUsernameFromToken(token);
			if (username == null) {
				return false;
			}
			UserDetails user = adminDetailsService.loadUserByUsername(username);
			return user != null && user.isEnabled()
					&& Boolean.TRUE.equals(jwtTokenUtil.validateToken(token, user, JWTTokenUtil.TOKEN_TYPE_ADMIN));
		} catch (RuntimeException e) {
			// bad signature, expired token, unknown user, customer token
			LOGGER.debug("admin token refresh refused: {}", e.getClass().getSimpleName());
			return false;
		}
	}
}
