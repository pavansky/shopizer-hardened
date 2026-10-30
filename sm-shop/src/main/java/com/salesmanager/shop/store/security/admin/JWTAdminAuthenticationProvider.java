package com.salesmanager.shop.store.security.admin;

import javax.inject.Inject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;


/**
 * Custom authautentication provider for admin api
 * @author carlsamson
 *
 */
public class JWTAdminAuthenticationProvider extends DaoAuthenticationProvider {
	
    @Autowired
    private UserDetailsService jwtAdminDetailsService;
    
    @Inject
    private PasswordEncoder passwordEncoder;

    static final String BAD_CREDENTIALS = "Bad credentials";

    private volatile String dummyHash;

	public UserDetailsService getJwtAdminDetailsService() {
		return jwtAdminDetailsService;
	}
	public void setJwtAdminDetailsService(UserDetailsService jwtAdminDetailsService) {
		this.jwtAdminDetailsService = jwtAdminDetailsService;
	}
	/**
	 * Every failure (unknown user, missing or wrong password, deactivated user)
	 * throws the same BadCredentialsException, so the login response does not
	 * tell an attacker which usernames exist or which accounts are disabled.
	 */
	@Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) authentication;
        String name = auth.getName();
        Object credentials = auth.getCredentials();
        UserDetails user = null;
        try {
            user = jwtAdminDetailsService.loadUserByUsername(name);
        } catch (UsernameNotFoundException e) {
            user = null;
        }

        if (user == null || credentials == null) {
            // spend the same hashing time as a real check
            passwordEncoder.matches(credentials == null ? "" : credentials.toString(), dummyHash());
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }

        // Compare the submitted password with the stored (encoded) password hash.
        if (!passwordMatch(credentials.toString(), user.getPassword()) || !user.isEnabled()) {
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }

        return new UsernamePasswordAuthenticationToken(user, credentials, user.getAuthorities());
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode("unused-password-for-timing");
            dummyHash = hash;
        }
        return hash;
    }

    private boolean passwordMatch(String rawPassword, String encodedPassword) {
		    return encodedPassword != null && passwordEncoder.matches(rawPassword, encodedPassword);
	}

    @Override
    public boolean supports(Class<?> authentication) {
        return true;
    }

}
