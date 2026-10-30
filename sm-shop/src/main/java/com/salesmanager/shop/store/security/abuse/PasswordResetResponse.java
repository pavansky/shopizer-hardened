package com.salesmanager.shop.store.security.abuse;

import java.util.Collections;
import java.util.Map;

/**
 * The single answer to a password reset request, for user and customer
 * accounts alike. It is returned whether or not the account exists and
 * whether or not the email could be sent, so the endpoint cannot be used to
 * find out which usernames are registered.
 */
public final class PasswordResetResponse {

	public static final String MESSAGE =
			"If an account exists for this username, a password reset link has been sent.";

	public static final Map<String, String> BODY = Collections.singletonMap("message", MESSAGE);

	private PasswordResetResponse() {
	}
}
