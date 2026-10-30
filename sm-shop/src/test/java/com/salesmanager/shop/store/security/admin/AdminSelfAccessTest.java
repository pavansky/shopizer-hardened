package com.salesmanager.shop.store.security.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.salesmanager.shop.store.security.user.JWTUser;

class AdminSelfAccessTest {

	private final AdminSelfAccess access = new AdminSelfAccess();

	private static UsernamePasswordAuthenticationToken admin(long id) {
		JWTUser user = new JWTUser(id, "staff@test.com", "S", "M", "staff@test.com", "x",
				Collections.emptyList(), true, null);
		return new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList());
	}

	@Test
	void onlyTheOwnIdMatches() {
		assertTrue(access.isSelf(admin(7), "7"));
		assertFalse(access.isSelf(admin(7), "1"));
		assertFalse(access.isSelf(admin(7), "07x"));
		assertFalse(access.isSelf(null, "7"));
		assertFalse(access.isSelf(new UsernamePasswordAuthenticationToken("staff@test.com", null), "7"));
	}
}
