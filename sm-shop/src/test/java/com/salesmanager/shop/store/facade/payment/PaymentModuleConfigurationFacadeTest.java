package com.salesmanager.shop.store.facade.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.system.audit.ConfigurationAuditService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.model.system.IntegrationModule;
import com.salesmanager.core.model.system.audit.ConfigurationAuditEntry;
import com.salesmanager.shop.model.system.IntegrationModuleConfiguration;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class PaymentModuleConfigurationFacadeTest {

	private static final String SECRET = "sk_test_abcdefgh1234";

	@Mock private PaymentService paymentService;
	@Mock private ConfigurationAuditService auditService;
	@InjectMocks private PaymentModuleConfigurationFacade facade;

	private final MerchantStore store = new MerchantStore();
	private IntegrationConfiguration stored;

	@BeforeEach
	void setUp() {
		store.setId(1);
		store.setCode("DEFAULT");
		stored = new IntegrationConfiguration();
		stored.setModuleCode("stripe");
		Map<String, String> keys = new HashMap<>();
		keys.put("secretKey", SECRET);
		keys.put("publishableKey", "pk_test_old_00001111");
		stored.setIntegrationKeys(keys);
	}

	private void stripeModuleKnown() throws Exception {
		IntegrationModule module = new IntegrationModule();
		module.setCode("stripe");
		when(paymentService.getPaymentMethods(store)).thenReturn(Arrays.asList(module));
	}

	private static IntegrationModuleConfiguration submitted(String secret, String publishable) {
		IntegrationModuleConfiguration c = new IntegrationModuleConfiguration();
		c.setCode("stripe");
		Map<String, String> keys = new HashMap<>();
		keys.put("secretKey", secret);
		keys.put("publishableKey", publishable);
		c.setIntegrationKeys(keys);
		return c;
	}

	@Test
	void readMasksSecrets() throws Exception {
		IntegrationModule module = new IntegrationModule();
		module.setCode("stripe");
		when(paymentService.getPaymentMethodByCode(store, "stripe")).thenReturn(module);
		when(paymentService.getPaymentConfiguration("stripe", store)).thenReturn(stored);

		IntegrationModuleConfiguration read = facade.read("stripe", store);

		assertEquals("****1234", read.getIntegrationKeys().get("secretKey"));
		assertEquals(stored.getIntegrationKeys().get("publishableKey"), read.getIntegrationKeys().get("publishableKey"), "publishable keys are public by design");
		assertEquals(SECRET, stored.getIntegrationKeys().get("secretKey"), "stored configuration is not modified");
	}

	@Test
	void maskedSecretIsSavedUnchangedAndOnlyRealChangeIsAudited() throws Exception {
		stripeModuleKnown();
		when(paymentService.getPaymentModulesConfigured(store)).thenReturn(Collections.singletonMap("stripe", stored));

		Set<String> changed = facade.save(submitted("****1234", "pk_test_new_00002222"), store, "admin@shopizer.com");

		ArgumentCaptor<IntegrationConfiguration> saved = ArgumentCaptor.forClass(IntegrationConfiguration.class);
		verify(paymentService).savePaymentModuleConfiguration(saved.capture(), eq(store));
		assertEquals(SECRET, saved.getValue().getIntegrationKeys().get("secretKey"));
		assertEquals("pk_test_new_00002222", saved.getValue().getIntegrationKeys().get("publishableKey"));
		assertEquals(Collections.singleton("publishableKey"), changed);
		verify(auditService).record(store, ConfigurationAuditEntry.TYPE_PAYMENT, "stripe", "admin@shopizer.com",
				changed);
	}

	@Test
	void unchangedSaveWritesNoAuditRow() throws Exception {
		stripeModuleKnown();
		when(paymentService.getPaymentModulesConfigured(store)).thenReturn(Collections.singletonMap("stripe", stored));

		Set<String> changed = facade.save(submitted("****1234", stored.getIntegrationKeys().get("publishableKey")), store, "admin@shopizer.com");

		assertTrue(changed.isEmpty());
		verify(auditService, never()).record(any(), anyString(), anyString(), anyString(), anyCollection());
	}

	@Test
	void unknownModuleIsNotFound() throws Exception {
		when(paymentService.getPaymentMethods(store)).thenReturn(Collections.emptyList());
		assertThrows(ResourceNotFoundException.class, () -> facade.save(submitted("a", "b"), store, "admin"));
		verify(paymentService, never()).savePaymentModuleConfiguration(any(), any());
	}
}
