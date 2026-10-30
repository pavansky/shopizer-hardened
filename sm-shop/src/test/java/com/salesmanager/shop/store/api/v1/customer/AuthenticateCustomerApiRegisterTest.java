package com.salesmanager.shop.store.api.v1.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.address.Address;
import com.salesmanager.shop.store.controller.customer.facade.CustomerFacade;
import com.salesmanager.shop.store.security.AbstractCustomerServices;
import com.salesmanager.shop.store.security.AuthenticationResponse;
import com.salesmanager.shop.store.security.JWTTokenUtil;
import com.salesmanager.shop.store.security.user.JWTUser;

/**
 * Registration answers the same way whether or not the email is already
 * registered in the store, and never creates a second account for it.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticateCustomerApiRegisterTest {

	@Mock private AuthenticationManager jwtCustomerAuthenticationManager;
	@Mock private JWTTokenUtil jwtTokenUtil;
	@Mock private AbstractCustomerServices jwtCustomerDetailsService;
	@Mock private CustomerFacade customerFacade;
	@InjectMocks private AuthenticateCustomerApi api;

	private final MerchantStore store = new MerchantStore();
	private final Language en = new Language();
	private final JWTUser user = new JWTUser(7L, "jane@example.com", "f", "l", "jane@example.com", "hash",
			Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH_CUSTOMER")), true, null);

	@BeforeEach
	void setUp() {
		store.setId(1);
		store.setCode("DEFAULT");
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private PersistableCustomer request(String password) {
		PersistableCustomer c = new PersistableCustomer();
		c.setEmailAddress("jane@example.com");
		c.setPassword(password);
		Address billing = new Address();
		billing.setCountry("CA");
		c.setBilling(billing);
		return c;
	}

	@Test
	void newEmailIsRegisteredAndGetsAToken() throws Exception {
		PersistableCustomer customer = request("secret1");
		when(customerFacade.checkIfUserExists("jane@example.com", store)).thenReturn(false);
		when(jwtCustomerAuthenticationManager.authenticate(any()))
				.thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
		when(jwtCustomerDetailsService.loadUserByUsernameAndStore("jane@example.com", "DEFAULT")).thenReturn(user);
		when(jwtTokenUtil.generateCustomerToken(user, "DEFAULT")).thenReturn("tok");

		ResponseEntity<?> response = api.register(customer, store, en);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		AuthenticationResponse body = (AuthenticationResponse) response.getBody();
		assertEquals(Long.valueOf(7L), body.getId());
		assertEquals("tok", body.getToken());
		verify(customerFacade).registerCustomer(customer, store, en);
	}

	@Test
	void existingEmailWithAnotherPasswordGetsTheSameStatusAndShapeWithoutAToken() throws Exception {
		PersistableCustomer customer = request("guess1");
		when(customerFacade.checkIfUserExists("jane@example.com", store)).thenReturn(true);
		when(jwtCustomerAuthenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("no"));

		ResponseEntity<?> response = api.register(customer, store, en);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		AuthenticationResponse body = (AuthenticationResponse) response.getBody();
		assertNull(body.getId());
		assertNull(body.getToken());
		verify(customerFacade, never()).registerCustomer(any(), any(), any());
		// the request is still converted and validated exactly as for a new email
		verify(customerFacade).getCustomerModel(customer, store, en);
	}

	@Test
	void existingEmailWithItsOwnPasswordIsALogin() throws Exception {
		PersistableCustomer customer = request("secret1");
		when(customerFacade.checkIfUserExists("jane@example.com", store)).thenReturn(true);
		when(jwtCustomerAuthenticationManager.authenticate(any()))
				.thenReturn(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
		when(jwtCustomerDetailsService.loadUserByUsernameAndStore("jane@example.com", "DEFAULT")).thenReturn(user);
		when(jwtTokenUtil.generateCustomerToken(user, "DEFAULT")).thenReturn("tok");

		ResponseEntity<?> response = api.register(customer, store, en);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals("tok", ((AuthenticationResponse) response.getBody()).getToken());
		verify(customerFacade, never()).registerCustomer(any(), any(), any());
	}
}
