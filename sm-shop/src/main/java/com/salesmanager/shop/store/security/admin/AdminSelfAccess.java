package com.salesmanager.shop.store.security.admin;

import org.springframework.security.core.Authentication;

import com.salesmanager.shop.store.security.user.JWTUser;

/**
 * Used by the admin URL rules as {@code @selfUser.isSelf(authentication, #id)}:
 * true when the {id} path variable is the signed-in admin's own user id.
 */
public class AdminSelfAccess {

	public boolean isSelf(Authentication authentication, String id) {
		if (authentication == null || id == null || !(authentication.getPrincipal() instanceof JWTUser)) {
			return false;
		}
		Long own = ((JWTUser) authentication.getPrincipal()).getId();
		return own != null && own.toString().equals(id.trim());
	}
}
