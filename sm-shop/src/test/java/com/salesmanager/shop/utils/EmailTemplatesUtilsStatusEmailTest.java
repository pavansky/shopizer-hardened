package com.salesmanager.shop.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salesmanager.core.business.modules.email.Email;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.system.EmailService;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.shop.constants.EmailConstants;

/** The order status email: recipient, names and the "Last order status: {0}" token. */
class EmailTemplatesUtilsStatusEmailTest {

	private EmailTemplatesUtils utils;
	private EmailService emailService;
	private LabelUtils messages;
	private final MerchantStore store = new MerchantStore();
	private final Order order = new Order();

	@BeforeEach
	void setUp() throws Exception {
		utils = new EmailTemplatesUtils();
		emailService = mock(EmailService.class);
		messages = mock(LabelUtils.class);
		EmailUtils emailUtils = mock(EmailUtils.class);
		when(emailUtils.createEmailObjectsMap(any(), any(), any(), any())).thenReturn(new HashMap<>());
		when(messages.getMessage(anyString(), any(Locale.class), anyString()))
				.thenAnswer(inv -> "label:" + inv.getArgument(2));
		when(messages.getMessage(anyString(), any(String[].class), any(Locale.class)))
				.thenAnswer(inv -> inv.getArgument(0) + ":" + String.join(",", (String[]) inv.getArgument(1)));
		inject("emailService", emailService);
		inject("messages", messages);
		inject("emailUtils", emailUtils);
		inject("orderService", mock(OrderService.class));
		inject("customerService", mock(CustomerService.class));

		store.setStorename("Demo");
		store.setStoreEmailAddress("shop@example.com");
		order.setId(9L);
		order.setStatus(OrderStatus.CANCELED);
		order.setDatePurchased(new Date());
		order.setCustomerEmailAddress("buyer@example.com");
		Billing billing = new Billing();
		billing.setFirstName("Ana");
		billing.setLastName("Lee");
		order.setBilling(billing);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = EmailTemplatesUtils.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(utils, value);
	}

	private OrderStatusHistory change(String comment) {
		OrderStatusHistory h = new OrderStatusHistory();
		h.setDateAdded(new Date());
		h.setComments(comment);
		return h;
	}

	@Test
	void statusTokenCarriesTheStatusLabelNotTheDate() throws Exception {
		utils.sendUpdateOrderStatusEmail(null, order, change("Canceled, refunded 249.00 CAD"), store, Locale.ENGLISH,
				"");

		ArgumentCaptor<String[]> args = ArgumentCaptor.forClass(String[].class);
		verify(messages).getMessage(eq("email.order.status"), args.capture(), eq(Locale.ENGLISH));
		assertArrayEquals(new String[] { "label:CANCELED" }, args.getValue());

		ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
		verify(emailService).sendHtmlEmail(eq(store), email.capture());
		assertEquals("buyer@example.com", email.getValue().getTo());
		assertEquals("email.order.status:label:CANCELED",
				email.getValue().getTemplateTokens().get(EmailConstants.EMAIL_ORDER_STATUS));
		assertEquals("Canceled, refunded 249.00 CAD",
				email.getValue().getTemplateTokens().get(EmailConstants.EMAIL_TEXT_STATUS_COMMENTS));
		assertEquals("Ana", email.getValue().getTemplateTokens().get(EmailConstants.EMAIL_CUSTOMER_FIRSTNAME));
	}

	@Test
	void noEmailAddressSendsNothing() throws Exception {
		order.setCustomerEmailAddress(null);

		utils.sendUpdateOrderStatusEmail(null, order, change("x"), store, Locale.ENGLISH, "");

		verify(emailService, never()).sendHtmlEmail(any(), any());
	}
}
