package com.salesmanager.shop.store.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.core.model.user.User;
import com.salesmanager.shop.store.security.admin.JWTAdminAuthenticationManager;
import com.salesmanager.shop.store.security.user.JWTUser;

/**
 * The admin user is reloaded on every request: a deactivated admin is refused
 * on the next call, and a token issued before the last password change is
 * refused although it has not expired.
 */
class AdminSessionRevocationTest {

	private static final String SECRET =
			"test-secret-0123456789-0123456789-0123456789-0123456789-0123456789";
	private static final String NAME = "staff@shopizer.com";

	private JWTTokenUtil util;
	private UserDetailsService details;
	private JWTAdminAuthenticationManager manager;

	@BeforeEach
	void setUp() {
		util = new JWTTokenUtil();
		ReflectionTestUtils.setField(util, "secret", SECRET);
		ReflectionTestUtils.setField(util, "expiration", 3600L);
		details = mock(UserDetailsService.class);
		manager = new JWTAdminAuthenticationManager();
		manager.setTokenHeader("Authorization");
		ReflectionTestUtils.setField(manager, "jwtTokenUtil", util);
		ReflectionTestUtils.setField(manager, "jwtAdminDetailsService", details);
		SecurityContextHolder.clearContext();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private static JWTUser user(boolean enabled, Date credentialsChangedAt) {
		return new JWTUser(7L, NAME, "f", "l", NAME, "hash",
				Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH")), enabled, credentialsChangedAt);
	}

	private Authentication authenticate(String token) {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/private/orders");
		req.addHeader("Authorization", "Bearer " + token);
		return manager.attemptAuthentication(req, new MockHttpServletResponse());
	}

	@Test
	void activeAdminWithCurrentTokenIsAuthenticated() {
		String token = util.generateToken(user(true, null));
		when(details.loadUserByUsername(NAME)).thenReturn(user(true, null));
		assertNotNull(authenticate(token));
	}

	@Test
	void deactivatedAdminIsRefusedOnTheNextRequest() {
		String token = util.generateToken(user(true, null));
		when(details.loadUserByUsername(NAME)).thenReturn(user(false, null));
		assertNull(authenticate(token));
	}

	@Test
	void tokenIssuedBeforeThePasswordChangeIsRefused() {
		String token = util.generateToken(user(true, null));
		Date issued = util.getIssuedAtDateFromToken(token);
		when(details.loadUserByUsername(NAME)).thenReturn(user(true, new Date(issued.getTime() + 1000)));
		assertNull(authenticate(token));
	}

	@Test
	void tokenIssuedInTheSameSecondAsTheChangeIsAccepted() {
		String token = util.generateToken(user(true, null));
		Date issued = util.getIssuedAtDateFromToken(token);
		when(details.loadUserByUsername(NAME)).thenReturn(user(true, issued));
		assertNotNull(authenticate(token));
	}

	@Test
	void replacingThePasswordRecordsTheChangeInWholeSeconds() {
		User u = new User();
		u.setAdminPassword("$2a$10$first");
		assertNull(u.getCredentialsChangedAt(), "the first password is not a change");

		long before = System.currentTimeMillis();
		u.setAdminPassword("$2a$10$second");
		Date changed = u.getCredentialsChangedAt();
		assertNotNull(changed);
		assertEquals(0, changed.getTime() % 1000);
		assertTrue(changed.getTime() >= (before / 1000) * 1000);

		u.setCredentialsChangedAt(null);
		u.setAdminPassword("$2a$10$second");
		assertNull(u.getCredentialsChangedAt(), "setting the same hash again is not a change");
	}
}
