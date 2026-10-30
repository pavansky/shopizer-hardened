package com.salesmanager.shop.store.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.salesmanager.shop.store.security.admin.JWTAdminAuthenticationManager;
import com.salesmanager.shop.store.security.common.CustomAuthenticationManager;
import com.salesmanager.shop.store.security.customer.JWTCustomerAuthenticationManager;
import com.salesmanager.shop.store.security.user.JWTUser;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/**
 * Token type claim: admin and customer tokens share a signing secret, so each
 * side must reject the other's tokens.
 */
class JWTTokenTypeTest {

	private static final String SECRET =
			"test-secret-0123456789-0123456789-0123456789-0123456789-0123456789";
	private static final String HEADER = "Authorization";

	private JWTTokenUtil util;

	@BeforeEach
	void setUp() {
		util = new JWTTokenUtil();
		ReflectionTestUtils.setField(util, "secret", SECRET);
		ReflectionTestUtils.setField(util, "expiration", 3600L);
		SecurityContextHolder.clearContext();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private static JWTUser user(String name, String role) {
		return new JWTUser(1L, name, "f", "l", name + "@example.com", "hash",
				Collections.singletonList(new SimpleGrantedAuthority(role)), true, null);
	}

	private static final JWTUser ADMIN = user("admin@shopizer.com", "ROLE_AUTH");
	private static final JWTUser CUSTOMER = user("admin@shopizer.com", "ROLE_AUTH_CUSTOMER");

	@Test
	void adminTokenCarriesAdminType() {
		String token = util.generateToken(ADMIN);
		assertEquals(JWTTokenUtil.TOKEN_TYPE_ADMIN, util.getTokenTypeFromToken(token));
		assertTrue(util.validateToken(token, ADMIN, JWTTokenUtil.TOKEN_TYPE_ADMIN));
		assertFalse(util.validateToken(token, ADMIN, JWTTokenUtil.TOKEN_TYPE_CUSTOMER));
	}

	@Test
	void customerTokenCarriesCustomerType() {
		String token = util.generateToken(CUSTOMER);
		assertEquals(JWTTokenUtil.TOKEN_TYPE_CUSTOMER, util.getTokenTypeFromToken(token));
		assertTrue(util.validateToken(token, CUSTOMER, JWTTokenUtil.TOKEN_TYPE_CUSTOMER));
		// same username, admin principal: must be refused
		assertFalse(util.validateToken(token, ADMIN, JWTTokenUtil.TOKEN_TYPE_ADMIN));
		assertFalse(util.validateToken(token, ADMIN));
	}

	@Test
	void refreshKeepsType() {
		String token = util.refreshToken(util.generateToken(CUSTOMER));
		assertEquals(JWTTokenUtil.TOKEN_TYPE_CUSTOMER, util.getTokenTypeFromToken(token));
	}

	@Test
	void untypedTokenIsRejected() {
		String legacy = Jwts.builder().setSubject(ADMIN.getUsername()).setIssuedAt(new Date())
				.setExpiration(new Date(System.currentTimeMillis() + 60_000))
				.signWith(SignatureAlgorithm.HS512, SECRET).compact();
		assertFalse(util.validateToken(legacy, ADMIN, JWTTokenUtil.TOKEN_TYPE_ADMIN));
	}

	@Test
	void ambiguousOrUnknownPrincipalCannotGetToken() {
		JWTUser none = user("x", "ROLE_OTHER");
		assertThrows(IllegalArgumentException.class, () -> util.generateToken(none));
		assertThrows(IllegalArgumentException.class, () -> util.generateToken(ADMIN, "root"));
	}

	@Test
	void shortSecretFailsStartup() {
		ReflectionTestUtils.setField(util, "secret", "too-short");
		assertThrows(IllegalStateException.class, util::checkSecret);
	}

	@Test
	void longSecretPasses() {
		util.checkSecret();
	}

	private Authentication attempt(CustomAuthenticationManager mgr, UserDetailsService uds, String token)
			throws Exception {
		ReflectionTestUtils.setField(mgr, "jwtTokenUtil", util);
		ReflectionTestUtils.setField(mgr, CustomAuthenticationManager.class, "tokenHeader", HEADER, String.class);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader(HEADER, "Bearer " + token);
		return mgr.attemptAuthentication(req, new MockHttpServletResponse());
	}

	@Test
	void adminManagerRejectsCustomerToken() throws Exception {
		UserDetailsService uds = mock(UserDetailsService.class);
		when(uds.loadUserByUsername(anyString())).thenReturn(ADMIN);
		JWTAdminAuthenticationManager mgr = new JWTAdminAuthenticationManager();
		ReflectionTestUtils.setField(mgr, "jwtAdminDetailsService", uds);

		assertNull(attempt(mgr, uds, util.generateToken(CUSTOMER)));
		assertTrue(attempt(mgr, uds, util.generateToken(ADMIN)) != null);
	}

	@Test
	void customerManagerRejectsAdminToken() throws Exception {
		AbstractCustomerServices uds = mock(AbstractCustomerServices.class);
		when(uds.loadUserByUsernameAndStore(anyString(), anyString())).thenReturn(CUSTOMER);
		JWTCustomerAuthenticationManager mgr = new JWTCustomerAuthenticationManager();
		ReflectionTestUtils.setField(mgr, "jwtCustomerDetailsService", uds);

		assertNull(attempt(mgr, uds, util.generateToken(ADMIN)));
		assertTrue(attempt(mgr, uds, util.generateCustomerToken(CUSTOMER, "DEFAULT")) != null);
	}
}
