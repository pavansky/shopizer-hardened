package com.salesmanager.shop.store.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.shop.store.security.admin.JWTAdminAuthenticationProvider;
import com.salesmanager.shop.store.security.user.JWTUser;

/** The submitted password must be checked against the stored hash, not the username. */
class JWTAdminAuthenticationProviderTest {

	private static final String USERNAME = "admin@shopizer.com";
	private static final String DISABLED = "disabled@shopizer.com";
	private JWTAdminAuthenticationProvider provider;

	@BeforeEach
	void setUp() {
		PasswordEncoder encoder = new BCryptPasswordEncoder(4);
		JWTUser user = new JWTUser(1L, USERNAME, "f", "l", USERNAME, encoder.encode("s3cret!"),
				Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH")), true, null);
		JWTUser disabled = new JWTUser(2L, DISABLED, "f", "l", DISABLED, encoder.encode("s3cret!"),
				Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH")), false, null);
		UserDetailsService uds = mock(UserDetailsService.class);
		when(uds.loadUserByUsername(USERNAME)).thenReturn(user);
		when(uds.loadUserByUsername(DISABLED)).thenReturn(disabled);
		when(uds.loadUserByUsername("nobody@shopizer.com")).thenThrow(new UsernameNotFoundException("User nobody not found"));

		provider = new JWTAdminAuthenticationProvider();
		provider.setJwtAdminDetailsService(uds);
		ReflectionTestUtils.setField(provider, "passwordEncoder", encoder);
	}

	@Test
	void correctPasswordAuthenticates() {
		assertNotNull(provider.authenticate(new UsernamePasswordAuthenticationToken(USERNAME, "s3cret!")));
	}

	@Test
	void wrongPasswordIsRejected() {
		assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken(USERNAME, "wrong")));
	}

	@Test
	void usernameAsPasswordIsRejected() {
		assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken(USERNAME, USERNAME)));
	}

	@Test
	void missingPasswordIsRejected() {
		assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken(USERNAME, null)));
	}

	/** Unknown user, wrong password and disabled account fail with the same exception and message. */
	@Test
	void unknownUserWrongPasswordAndDisabledAccountAreIndistinguishable() {
		BadCredentialsException unknown = assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken("nobody@shopizer.com", "s3cret!")));
		BadCredentialsException wrong = assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken(USERNAME, "wrong")));
		BadCredentialsException disabled = assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken(DISABLED, "s3cret!")));
		assertEquals(wrong.getMessage(), unknown.getMessage());
		assertEquals(wrong.getMessage(), disabled.getMessage());
		assertEquals("Bad credentials", wrong.getMessage());
	}
}
