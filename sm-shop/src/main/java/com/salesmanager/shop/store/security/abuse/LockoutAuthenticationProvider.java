package com.salesmanager.shop.store.security.abuse;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Wraps the provider that checks passwords with a per-account lockout.
 *
 * Every failure (wrong password, unknown user, disabled user) counts. A locked
 * account fails with the same BadCredentialsException as a wrong password, so
 * the login endpoints return the same status and body in every case.
 */
public class LockoutAuthenticationProvider implements AuthenticationProvider {

	static final String MESSAGE = "Bad credentials";

	private final String realm;
	private final AuthenticationProvider delegate;
	private final LoginAttemptTracker tracker;

	public LockoutAuthenticationProvider(String realm, AuthenticationProvider delegate, LoginAttemptTracker tracker) {
		this.realm = realm;
		this.delegate = delegate;
		this.tracker = tracker;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String key = LoginAttemptTracker.key(realm, authentication.getName());
		if (tracker.isLocked(key)) {
			throw new BadCredentialsException(MESSAGE);
		}
		Authentication result;
		try {
			result = delegate.authenticate(authentication);
		} catch (AuthenticationException e) {
			tracker.recordFailure(key);
			throw new BadCredentialsException(MESSAGE);
		}
		if (result != null) {
			tracker.recordSuccess(key);
		}
		return result;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return delegate.supports(authentication);
	}
}
