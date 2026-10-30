package com.salesmanager.shop.store.security.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.shop.store.security.JWTTokenUtil;
import com.salesmanager.shop.store.security.user.JWTUser;

/** The admin token refresh only renews tokens the admin API would still accept. */
class AdminTokenRefreshGuardTest {

	private static final String SECRET =
			"test-secret-0123456789-0123456789-0123456789-0123456789-0123456789";
	private static final String NAME = "staff@shopizer.com";

	private JWTTokenUtil util;
	private UserDetailsService details;
	private AdminTokenRefreshGuard guard;

	@BeforeEach
	void setUp() {
		util = new JWTTokenUtil();
		ReflectionTestUtils.setField(util, "secret", SECRET);
		ReflectionTestUtils.setField(util, "expiration", 3600L);
		details = mock(UserDetailsService.class);
		guard = new AdminTokenRefreshGuard(util, details, "Authorization");
	}

	private static JWTUser admin(boolean enabled, Date credentialsChangedAt) {
		return new JWTUser(7L, NAME, "f", "l", NAME, "hash",
				Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH")), enabled, credentialsChangedAt);
	}

	private static JWTUser customer() {
		return new JWTUser(8L, NAME, "f", "l", NAME, "hash",
				Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH_CUSTOMER")), true, null);
	}

	/** Runs the guard; returns the status, or 0 when the request reached the handler. */
	private int refresh(String path, String authorization) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		guard.doFilter(request, response, chain);
		if (chain.getRequest() != null) {
			return 0;
		}
		assertNull(response.getHeader("WWW-Authenticate"), "no Basic challenge, the browser must not prompt");
		assertEquals(AdminTokenRefreshGuard.BODY, response.getContentAsString());
		return response.getStatus();
	}

	@Test
	void currentTokenOfAnActiveAdminIsRefreshed() throws Exception {
		String token = util.generateToken(admin(true, null));
		when(details.loadUserByUsername(NAME)).thenReturn(admin(true, null));
		assertEquals(0, refresh("/api/v1/auth/refresh", "Bearer " + token));
	}

	@Test
	void tokenIssuedBeforeThePasswordChangeIsNotRefreshed() throws Exception {
		String token = util.generateToken(admin(true, null));
		Date issued = util.getIssuedAtDateFromToken(token);
		when(details.loadUserByUsername(NAME)).thenReturn(admin(true, new Date(issued.getTime() + 1000)));
		assertEquals(401, refresh("/api/v1/auth/refresh", "Bearer " + token));
	}

	@Test
	void deactivatedAdminIsNotRefreshed() throws Exception {
		String token = util.generateToken(admin(true, null));
		when(details.loadUserByUsername(NAME)).thenReturn(admin(false, null));
		assertEquals(401, refresh("/api/v1/auth/refresh", "Bearer " + token));
	}

	@Test
	void customerTokenIsNotRefreshedAsAnAdminToken() throws Exception {
		String token = util.generateToken(customer());
		when(details.loadUserByUsername(NAME)).thenReturn(admin(true, null));
		assertEquals(401, refresh("/api/v1/auth/refresh", "Bearer " + token));
	}

	@Test
	void missingForgedOrUnknownTokensAreRefused() throws Exception {
		assertEquals(401, refresh("/api/v1/auth/refresh", null));
		assertEquals(401, refresh("/api/v1/auth/refresh", "Bearer not-a-token"));
		String token = util.generateToken(admin(true, null));
		when(details.loadUserByUsername(NAME)).thenThrow(new UsernameNotFoundException(NAME));
		assertEquals(401, refresh("/api/v1/auth/refresh", "Bearer " + token));
	}

	@Test
	void otherPathsAreNotTouched() throws Exception {
		assertEquals(0, refresh("/api/v1/auth/customer/refresh", null));
		assertEquals(0, refresh("/api/v1/auth/customers/profile", null));
		MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
		MockFilterChain chain = new MockFilterChain();
		guard.doFilter(post, new MockHttpServletResponse(), chain);
		assertSame(post, chain.getRequest());
	}
}
