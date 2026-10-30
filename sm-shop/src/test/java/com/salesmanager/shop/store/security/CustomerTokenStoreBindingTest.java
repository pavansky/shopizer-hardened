package com.salesmanager.shop.store.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.user.GroupService;
import com.salesmanager.core.business.services.user.PermissionService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.shop.store.security.common.CustomAuthenticationManager;
import com.salesmanager.shop.store.security.customer.JWTCustomerAuthenticationManager;
import com.salesmanager.shop.store.security.customer.JWTCustomerServicesImpl;
import com.salesmanager.shop.store.security.user.JWTUser;

/**
 * Customer identity is per store: the customer is loaded in the request's
 * store, the token records that store, and a token is refused on another store.
 */
class CustomerTokenStoreBindingTest {

	private static final String SECRET =
			"test-secret-0123456789-0123456789-0123456789-0123456789-0123456789";
	private static final String HEADER = "Authorization";

	private static final JWTUser CUSTOMER = new JWTUser(7L, "jane@example.com", "f", "l", "jane@example.com",
			"hash", Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH_CUSTOMER")), true, null);
	private static final JWTUser ADMIN = new JWTUser(1L, "admin@shopizer.com", "f", "l", "admin@shopizer.com",
			"hash", Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTH")), true, null);

	private JWTTokenUtil util;

	@BeforeEach
	void setUp() {
		util = new JWTTokenUtil();
		ReflectionTestUtils.setField(util, "secret", SECRET);
		ReflectionTestUtils.setField(util, "expiration", 3600L);
		SecurityContextHolder.clearContext();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	// ------------------------------------------------------------ token

	@Test
	void customerTokenCarriesStoreAndRefreshKeepsIt() {
		String token = util.generateCustomerToken(CUSTOMER, "STORE_B");
		assertEquals("STORE_B", util.getStoreFromToken(token));
		assertEquals(JWTTokenUtil.TOKEN_TYPE_CUSTOMER, util.getTokenTypeFromToken(token));
		assertEquals("STORE_B", util.getStoreFromToken(util.refreshToken(token)));
	}

	@Test
	void customerTokenNeedsAStoreAndACustomer() {
		assertThrows(IllegalArgumentException.class, () -> util.generateCustomerToken(CUSTOMER, " "));
		assertThrows(IllegalArgumentException.class, () -> util.generateCustomerToken(CUSTOMER, null));
		assertThrows(IllegalArgumentException.class, () -> util.generateCustomerToken(ADMIN, "DEFAULT"));
	}

	// ------------------------------------------------------------ token validation

	private Authentication attempt(AbstractCustomerServices uds, String token, String storeParam) throws Exception {
		JWTCustomerAuthenticationManager mgr = new JWTCustomerAuthenticationManager();
		ReflectionTestUtils.setField(mgr, "jwtTokenUtil", util);
		ReflectionTestUtils.setField(mgr, "jwtCustomerDetailsService", uds);
		ReflectionTestUtils.setField(mgr, CustomAuthenticationManager.class, "tokenHeader", HEADER, String.class);
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader(HEADER, "Bearer " + token);
		if (storeParam != null) {
			req.setParameter("store", storeParam);
		}
		return mgr.attemptAuthentication(req, new MockHttpServletResponse());
	}

	@Test
	void tokenIsAcceptedOnItsOwnStoreAndLoadsTheCustomerThere() throws Exception {
		AbstractCustomerServices uds = mock(AbstractCustomerServices.class);
		when(uds.loadUserByUsernameAndStore("jane@example.com", "STORE_B")).thenReturn(CUSTOMER);

		assertNotNull(attempt(uds, util.generateCustomerToken(CUSTOMER, "STORE_B"), "STORE_B"));
		verify(uds).loadUserByUsernameAndStore("jane@example.com", "STORE_B");
	}

	@Test
	void missingStoreParameterMeansTheDefaultStore() throws Exception {
		AbstractCustomerServices uds = mock(AbstractCustomerServices.class);
		when(uds.loadUserByUsernameAndStore("jane@example.com", "DEFAULT")).thenReturn(CUSTOMER);

		assertNotNull(attempt(uds, util.generateCustomerToken(CUSTOMER, "DEFAULT"), null));
	}

	@Test
	void tokenOfAnotherStoreIsRefusedWithoutLoadingAnyone() throws Exception {
		AbstractCustomerServices uds = mock(AbstractCustomerServices.class);

		assertNull(attempt(uds, util.generateCustomerToken(CUSTOMER, "STORE_B"), "DEFAULT"));
		assertNull(attempt(uds, util.generateCustomerToken(CUSTOMER, "STORE_B"), null));
		verify(uds, never()).loadUserByUsernameAndStore(anyString(), anyString());
	}

	@Test
	void tokenWithoutStoreClaimIsRefused() throws Exception {
		AbstractCustomerServices uds = mock(AbstractCustomerServices.class);

		assertNull(attempt(uds, util.generateToken(CUSTOMER), null));
		verify(uds, never()).loadUserByUsernameAndStore(anyString(), anyString());
	}

	// ------------------------------------------------------------ customer lookup

	private static Customer customer() {
		Customer c = new Customer();
		c.setId(7L);
		c.setNick("jane@example.com");
		c.setPassword("hash");
		c.setBilling(new Billing());
		return c;
	}

	@Test
	void requestStoreCodeDefaultsToDefaultStore() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		assertEquals("DEFAULT", AbstractCustomerServices.requestStoreCode(req));
		req.setParameter("store", "");
		assertEquals("DEFAULT", AbstractCustomerServices.requestStoreCode(req));
		req.setParameter("store", "STORE_B");
		assertEquals("STORE_B", AbstractCustomerServices.requestStoreCode(req));
		assertEquals("DEFAULT", AbstractCustomerServices.requestStoreCode(null));
	}

	@Test
	void loadUserByUsernameLooksUpTheCustomerInTheRequestStore() {
		CustomerService customerService = mock(CustomerService.class);
		when(customerService.getByNick("jane@example.com", "STORE_B")).thenReturn(customer());
		JWTCustomerServicesImpl services = new JWTCustomerServicesImpl(customerService,
				mock(PermissionService.class), mock(GroupService.class));

		MockHttpServletRequest req = new MockHttpServletRequest();
		req.setParameter("store", "STORE_B");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

		JWTUser loaded = (JWTUser) services.loadUserByUsername("jane@example.com");
		assertEquals(Long.valueOf(7L), loaded.getId());
		verify(customerService).getByNick(eq("jane@example.com"), eq("STORE_B"));
		verify(customerService, never()).getByNick("jane@example.com");
	}

	@Test
	void customerOfAnotherStoreIsNotFound() {
		CustomerService customerService = mock(CustomerService.class);
		when(customerService.getByNick("jane@example.com", "STORE_B")).thenReturn(customer());
		JWTCustomerServicesImpl services = new JWTCustomerServicesImpl(customerService,
				mock(PermissionService.class), mock(GroupService.class));

		assertThrows(UsernameNotFoundException.class,
				() -> services.loadUserByUsernameAndStore("jane@example.com", "DEFAULT"));
	}
}
