package com.salesmanager.shop.store.facade.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserFacadeResetOriginTest {

	private UserFacadeImpl facade() {
		UserFacadeImpl facade = new UserFacadeImpl();
		ReflectionTestUtils.setField(facade, "allowedResetOrigins", "http://localhost:4200, https://admin.example.com");
		return facade;
	}

	@Test
	void allowsConfiguredOrigins() {
		assertTrue(facade().isAllowedResetOrigin("http://localhost:4200"));
		assertTrue(facade().isAllowedResetOrigin("https://admin.example.com/some/path"));
	}

	@Test
	void rejectsOtherHostsSchemesAndPorts() {
		assertFalse(facade().isAllowedResetOrigin("https://attacker.example"));
		assertFalse(facade().isAllowedResetOrigin("http://admin.example.com"));
		assertFalse(facade().isAllowedResetOrigin("http://localhost:4201"));
		assertFalse(facade().isAllowedResetOrigin("not a url"));
	}
}
