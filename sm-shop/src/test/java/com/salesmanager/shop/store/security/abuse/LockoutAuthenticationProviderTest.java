package com.salesmanager.shop.store.security.abuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Per-account lockout: N failures lock the account, even the right password then fails the same way. */
class LockoutAuthenticationProviderTest {

	private static final String USER = "admin@shopizer.com";

	private final AtomicLong now = new AtomicLong(1_000_000L);
	private AuthenticationProvider delegate;
	private LockoutAuthenticationProvider provider;

	private final UsernamePasswordAuthenticationToken good = new UsernamePasswordAuthenticationToken(USER, "password");
	private final UsernamePasswordAuthenticationToken bad = new UsernamePasswordAuthenticationToken(USER, "wrong");

	@BeforeEach
	void setUp() {
		delegate = mock(AuthenticationProvider.class);
		when(delegate.authenticate(any())).thenAnswer(inv -> {
			Authentication a = inv.getArgument(0);
			if ("password".equals(a.getCredentials())) {
				return new UsernamePasswordAuthenticationToken(a.getName(), null, null);
			}
			throw new BadCredentialsException("Username/Password does not match");
		});
		LoginAttemptTracker tracker = new LoginAttemptTracker(3, 900, 300, now::get);
		provider = new LockoutAuthenticationProvider("admin", delegate, tracker, new ClientIpResolver(""));
	}

	@Test
	void locksAfterMaxFailuresAndRejectsTheRightPasswordWithTheSameError() {
		for (int i = 0; i < 3; i++) {
			BadCredentialsException e = assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
			assertEquals("Bad credentials", e.getMessage());
		}
		reset(delegate);

		BadCredentialsException locked = assertThrows(BadCredentialsException.class, () -> provider.authenticate(good));
		assertEquals("Bad credentials", locked.getMessage());
		// the password is not even checked while locked
		verify(delegate, never()).authenticate(any());
	}

	@Test
	void lockExpires() {
		for (int i = 0; i < 3; i++) {
			assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		}
		now.addAndGet(301_000L);
		assertNotNull(provider.authenticate(good));
	}

	@Test
	void successResetsTheCount() {
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		assertNotNull(provider.authenticate(good));
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		assertNotNull(provider.authenticate(good));
	}

	@Test
	void usernamesAreCaseInsensitiveAndRealmsAreSeparate() {
		LoginAttemptTracker tracker = new LoginAttemptTracker(2, 900, 300, now::get);
		tracker.recordFailure(LoginAttemptTracker.key("admin", "Admin@Shopizer.com"));
		tracker.recordFailure(LoginAttemptTracker.key("admin", " admin@shopizer.com"));
		assertTrue(tracker.isLocked(LoginAttemptTracker.key("admin", USER)));
		assertFalse(tracker.isLocked(LoginAttemptTracker.key("customer", USER)));
	}

	@Test
	void failuresOutsideTheWindowDoNotAccumulate() {
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		now.addAndGet(901_000L);
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		assertNotNull(provider.authenticate(good));
	}

	@AfterEach
	void clearRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	private static void requestFrom(String address) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/private/login");
		request.setRemoteAddr(address);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	@Test
	void failuresFromOneAddressDoNotLockTheOwnerOutElsewhere() {
		requestFrom("203.0.113.9");
		for (int i = 0; i < 3; i++) {
			assertThrows(BadCredentialsException.class, () -> provider.authenticate(bad));
		}
		// the attacking address is locked, even with the right password
		assertThrows(BadCredentialsException.class, () -> provider.authenticate(good));

		// the owner signing in from their own address is not
		requestFrom("198.51.100.20");
		assertNotNull(provider.authenticate(good));
	}
}
