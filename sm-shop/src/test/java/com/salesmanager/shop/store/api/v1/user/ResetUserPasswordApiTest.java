package com.salesmanager.shop.store.api.v1.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.shop.store.api.exception.ServiceRuntimeException;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.store.controller.user.facade.UserFacade;
import com.salesmanager.shop.store.security.ResetPasswordRequest;
import com.salesmanager.shop.store.security.abuse.PasswordResetResponse;

/** The reset request answers the same way when the facade succeeds and when it fails. */
class ResetUserPasswordApiTest {

	@Test
	void knownAndUnknownUsersGetTheSameAnswer() {
		UserFacade facade = mock(UserFacade.class);
		doThrow(new ServiceRuntimeException("User [ghost] not found for store [DEFAULT]"))
				.when(facade).requestPasswordReset(eq("ghost"), any(), any(), any());
		ResetUserPasswordApi api = new ResetUserPasswordApi();
		ReflectionTestUtils.setField(api, "userFacade", facade);

		ResetPasswordRequest known = new ResetPasswordRequest("admin@shopizer.com");
		known.setReturnUrl("http://localhost:4200");
		ResetPasswordRequest ghost = new ResetPasswordRequest("ghost");
		ghost.setReturnUrl("http://localhost:4200");

		Object a = api.passwordResetRequest(new MerchantStore(), new Language(), known, null);
		Object b = api.passwordResetRequest(new MerchantStore(), new Language(), ghost, null);

		verify(facade).requestPasswordReset(eq("admin@shopizer.com"), any(), any(), any());
		assertEquals(PasswordResetResponse.BODY, a);
		assertEquals(a, b);
	}
}
