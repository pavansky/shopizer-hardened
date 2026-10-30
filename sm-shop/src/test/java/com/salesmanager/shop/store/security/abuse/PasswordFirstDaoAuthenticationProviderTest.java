package com.salesmanager.shop.store.security.abuse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A disabled account costs the same password hash as an active one before it is refused. */
class PasswordFirstDaoAuthenticationProviderTest {

	private UserDetailsService users;
	private PasswordEncoder encoder;
	private PasswordFirstDaoAuthenticationProvider provider;

	@BeforeEach
	void setUp() throws Exception {
		users = mock(UserDetailsService.class);
		encoder = mock(PasswordEncoder.class);
		when(encoder.matches(eq("right"), eq("HASH"))).thenReturn(true);
		when(encoder.matches(eq("wrong"), anyString())).thenReturn(false);
		provider = new PasswordFirstDaoAuthenticationProvider(users, encoder);
		provider.afterPropertiesSet();
	}

	private void account(boolean enabled) {
		when(users.loadUserByUsername("staff")).thenReturn(
				new User("staff", "HASH", enabled, true, true, true, Collections.emptyList()));
	}

	@Test
	void disabledAccountHashesThePasswordBeforeItIsRefused() {
		account(false);
		assertThrows(BadCredentialsException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken("staff", "wrong")));
		verify(encoder).matches("wrong", "HASH");

		assertThrows(DisabledException.class,
				() -> provider.authenticate(new UsernamePasswordAuthenticationToken("staff", "right")));
		verify(encoder).matches("right", "HASH");
	}

	@Test
	void activeAccountWithTheRightPasswordSignsIn() {
		account(true);
		assertNotNull(provider.authenticate(new UsernamePasswordAuthenticationToken("staff", "right")));
	}
}
