package com.salesmanager.shop.store.security.abuse;

import javax.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Wraps the provider that checks passwords with a lockout per account and
 * client address, and a higher one per account alone (see LoginAttemptTracker).
 *
 * Every failure (wrong password, unknown user, disabled user) counts. A locked
 * account fails with the same BadCredentialsException as a wrong password, so
 * the login endpoints return the same status and body in every case.
 *
 * The low threshold is keyed on the account and the client address (resolved
 * with the trusted-proxy rules), so failures from one address do not lock the
 * owner out when they sign in from another. The account-wide threshold caps
 * guessing spread over many addresses.
 */
public class LockoutAuthenticationProvider implements AuthenticationProvider {

	static final String MESSAGE = "Bad credentials";

	private final String realm;
	private final AuthenticationProvider delegate;
	private final LoginAttemptTracker tracker;
	private final ClientIpResolver clientIpResolver;

	public LockoutAuthenticationProvider(String realm, AuthenticationProvider delegate, LoginAttemptTracker tracker,
			ClientIpResolver clientIpResolver) {
		this.realm = realm;
		this.delegate = delegate;
		this.tracker = tracker;
		this.clientIpResolver = clientIpResolver;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		String account = LoginAttemptTracker.key(realm, authentication.getName());
		String address = clientAddress();
		if (tracker.isLocked(account, address)) {
			throw new BadCredentialsException(MESSAGE);
		}
		Authentication result;
		try {
			result = delegate.authenticate(authentication);
		} catch (AuthenticationException e) {
			tracker.recordFailure(account, address);
			throw new BadCredentialsException(MESSAGE);
		}
		if (result != null) {
			tracker.recordSuccess(account, address);
		}
		return result;
	}

	/** The client address of the current request, or empty when there is no request (tests, batch). */
	private String clientAddress() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (clientIpResolver == null || !(attributes instanceof ServletRequestAttributes)) {
			return "";
		}
		HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
		String address = clientIpResolver.resolve(request);
		return address == null ? "" : address;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return delegate.supports(authentication);
	}
}
