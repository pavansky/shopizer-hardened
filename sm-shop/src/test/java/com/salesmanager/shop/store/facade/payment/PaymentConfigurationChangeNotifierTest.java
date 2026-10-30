package com.salesmanager.shop.store.facade.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.salesmanager.core.business.modules.email.Email;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.system.EmailService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.shop.constants.EmailConstants;
import com.salesmanager.shop.utils.EmailUtils;
import com.salesmanager.shop.utils.LabelUtils;

@ExtendWith(MockitoExtension.class)
class PaymentConfigurationChangeNotifierTest {

	@Mock private EmailService emailService;
	@Mock private EmailUtils emailUtils;
	@Mock private LabelUtils messages;
	@Mock private MerchantStoreService merchantStoreService;
	@InjectMocks private PaymentConfigurationChangeNotifier notifier;

	@Test
	void emailGoesToStoreAddressAndNamesSettingsOnly() throws Exception {
		MerchantStore store = new MerchantStore();
		store.setCode("DEFAULT");
		store.setStorename("Demo Store");
		store.setStoreEmailAddress("owner@example.com");
		when(merchantStoreService.getByCode("DEFAULT")).thenReturn(store);
		when(emailUtils.createEmailObjectsMap(anyString(), eq(store), eq(messages), any())).thenReturn(new HashMap<>());

		notifier.paymentConfigurationChanged("DEFAULT", "stripe", "admin@shopizer.com",
				Arrays.asList("publishableKey", "secretKey"));

		ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
		verify(emailService).sendHtmlEmail(eq(store), email.capture());
		assertEquals("owner@example.com", email.getValue().getTo());
		assertEquals(EmailConstants.EMAIL_NOTIFICATION_TMPL, email.getValue().getTemplateName());
		String body = email.getValue().getTemplateTokens().get(EmailConstants.EMAIL_NOTIFICATION_MESSAGE);
		assertTrue(body.contains("stripe"));
		assertTrue(body.contains("admin@shopizer.com"));
		assertTrue(body.contains("publishableKey, secretKey"));
	}

	@Test
	void storeWithoutEmailIsSkipped() throws Exception {
		MerchantStore store = new MerchantStore();
		store.setCode("DEFAULT");
		when(merchantStoreService.getByCode("DEFAULT")).thenReturn(store);

		notifier.paymentConfigurationChanged("DEFAULT", "stripe", "admin", Arrays.asList("secretKey"));

		verify(emailService, never()).sendHtmlEmail(any(), any());
	}
}
