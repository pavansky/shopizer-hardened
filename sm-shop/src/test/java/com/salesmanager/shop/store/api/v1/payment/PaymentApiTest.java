package com.salesmanager.shop.store.api.v1.payment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.model.system.IntegrationModuleConfiguration;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.exception.UnauthorizedException;
import com.salesmanager.shop.store.facade.payment.PaymentConfigurationChangeNotifier;
import com.salesmanager.shop.store.facade.payment.PaymentModuleConfigurationFacade;
import com.salesmanager.shop.utils.AuthorizationUtils;

@ExtendWith(MockitoExtension.class)
class PaymentApiTest {

	@Mock private PaymentService paymentService;
	@Mock private PaymentModuleConfigurationFacade facade;
	@Mock private PaymentConfigurationChangeNotifier notifier;
	@Mock private AuthorizationUtils authorizationUtils;
	@InjectMocks private PaymentApi api;

	private final MerchantStore store = new MerchantStore();
	private final IntegrationModuleConfiguration configuration = new IntegrationModuleConfiguration();

	@BeforeEach
	void setUp() {
		store.setCode("DEFAULT");
		configuration.setCode("stripe");
	}

	@Test
	void onlyStoreAdminsAreAllowed() throws Exception {
		when(authorizationUtils.authenticatedUser()).thenReturn("admin@shopizer.com");
		when(facade.save(configuration, store, "admin@shopizer.com")).thenReturn(Collections.emptySet());

		api.configure(configuration, store);

		verify(authorizationUtils).authorizeUser("admin@shopizer.com",
				Arrays.asList(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN), store);
	}

	@Test
	void changeNotifiesStoreOwner() throws Exception {
		when(authorizationUtils.authenticatedUser()).thenReturn("admin@shopizer.com");
		Set<String> changed = new LinkedHashSet<>(Arrays.asList("secretKey"));
		when(facade.save(configuration, store, "admin@shopizer.com")).thenReturn(changed);

		api.configure(configuration, store);

		verify(notifier).paymentConfigurationChanged("DEFAULT", "stripe", "admin@shopizer.com", changed);
	}

	@Test
	void noChangeSendsNoEmail() throws Exception {
		when(authorizationUtils.authenticatedUser()).thenReturn("admin@shopizer.com");
		when(facade.save(configuration, store, "admin@shopizer.com")).thenReturn(Collections.emptySet());

		api.configure(configuration, store);

		verify(notifier, never()).paymentConfigurationChanged(anyString(), anyString(), anyString(), anyCollection());
	}

	@Test
	void deniedUserTouchesNothing() throws Exception {
		when(authorizationUtils.authenticatedUser()).thenReturn("clerk@shopizer.com");
		doThrow(new UnauthorizedException("no")).when(authorizationUtils).authorizeUser(eq("clerk@shopizer.com"),
				anyList(), any());

		assertThrows(UnauthorizedException.class, () -> api.configure(configuration, store));
		assertThrows(UnauthorizedException.class, () -> api.paymentModule("stripe", store, null));
		assertThrows(UnauthorizedException.class, () -> api.paymentModules(store, null));

		verifyNoInteractions(facade, notifier, paymentService);
	}

	@Test
	void userLookupFailureDuringAuthorizationIsForbiddenNotServerError() throws Exception {
		when(authorizationUtils.authenticatedUser()).thenReturn("deleted@shopizer.com");
		doThrow(new ResourceNotFoundException("User [deleted@shopizer.com] not found")).when(authorizationUtils)
				.authorizeUser(eq("deleted@shopizer.com"), anyList(), any());

		assertThrows(UnauthorizedException.class, () -> api.paymentModule("stripe", store, null));

		verifyNoInteractions(facade, notifier, paymentService);
	}
}
