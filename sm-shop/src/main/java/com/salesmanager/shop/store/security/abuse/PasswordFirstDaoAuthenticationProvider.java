package com.salesmanager.shop.store.security.abuse;

import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * DaoAuthenticationProvider that checks the password before the account
 * status.
 *
 * The stock provider rejects a disabled, locked or expired account before it
 * hashes the submitted password, so such an account answers measurably faster
 * than a wrong password and its existence leaks through timing. Here the
 * password is always hashed first; the status checks run afterwards. The
 * caller (LockoutAuthenticationProvider) turns every failure into the same
 * BadCredentialsException.
 */
public class PasswordFirstDaoAuthenticationProvider extends DaoAuthenticationProvider {

	public PasswordFirstDaoAuthenticationProvider(UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		setUserDetailsService(userDetailsService);
		setPasswordEncoder(passwordEncoder);
		setPreAuthenticationChecks(user -> {
		});
		// locked, disabled, account expired, credentials expired
		setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
	}
}
