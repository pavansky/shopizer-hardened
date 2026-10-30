package com.salesmanager.shop.store.controller.order.facade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderPaidEvent;
import com.salesmanager.core.business.utils.CoreConfiguration;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.utils.EmailTemplatesUtils;

/** The order confirmation sent when the reconciler settles a PAYMENT_PENDING order as paid. */
class PendingOrderConfirmationTest {

	private PendingOrderConfirmation listener;
	private OrderService orderService;
	private CustomerService customerService;
	private EmailTemplatesUtils emails;
	private CoreConfiguration configuration;
	private final MerchantStore store = new MerchantStore();
	private final Order order = new Order();
	private final Customer customer = new Customer();

	@BeforeEach
	void setUp() throws Exception {
		Language language = new Language();
		language.setCode("en");
		store.setId(1);
		store.setCode("DEFAULT");
		store.setDefaultLanguage(language);
		store.setStoreEmailAddress("shop@example.com");
		order.setId(9L);
		order.setMerchant(store);
		order.setCustomerId(3L);
		order.setCustomerEmailAddress("buyer@example.com");
		customer.setId(3L);
		customer.setEmailAddress("account@example.com");

		listener = new PendingOrderConfirmation();
		orderService = mock(OrderService.class);
		customerService = mock(CustomerService.class);
		emails = mock(EmailTemplatesUtils.class);
		configuration = mock(CoreConfiguration.class);
		when(configuration.getProperty("ORDER_EMAIL_API")).thenReturn("true");
		when(orderService.getById(9L)).thenReturn(order);
		when(customerService.getById(3L)).thenReturn(customer);
		inject("orderService", orderService);
		inject("customerService", customerService);
		inject("emailTemplatesUtils", emails);
		inject("coreConfiguration", configuration);
	}

	private void inject(String name, Object value) throws Exception {
		Field f = PendingOrderConfirmation.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(listener, value);
	}

	@Test
	void sendsToTheOrderAddressAndToTheStore() {
		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(emails).sendOrderEmail(eq("buyer@example.com"), eq(customer), eq(order), any(), any(), eq(store),
				any());
		verify(emails).sendOrderEmail(eq("shop@example.com"), eq(customer), eq(order), any(), any(), eq(store),
				any());
		verify(emails, never()).sendOrderEmail(eq("account@example.com"), any(), any(), any(), any(), any(), any());
	}

	@Test
	void fallsBackToTheCustomerAddress() {
		order.setCustomerEmailAddress(" ");

		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(emails).sendOrderEmail(eq("account@example.com"), eq(customer), eq(order), any(), any(), eq(store),
				any());
	}

	@Test
	void skipsTheStoreCopyWithoutAStoreAddress() {
		store.setStoreEmailAddress("");

		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(emails).sendOrderEmail(eq("buyer@example.com"), any(), any(), any(), any(), any(), any());
		verify(emails, never()).sendOrderEmail(eq(""), any(), any(), any(), any(), any(), any());
	}

	@Test
	void sendsOnlyTheStoreCopyWithoutAnyCustomerAddress() {
		order.setCustomerEmailAddress(null);
		order.setCustomerId(null);

		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(emails).sendOrderEmail(eq("shop@example.com"), eq(null), eq(order), any(), any(), eq(store), any());
		verify(emails, never()).sendOrderEmail(eq("buyer@example.com"), any(), any(), any(), any(), any(), any());
	}

	@Test
	void sendsNothingWhenOrderEmailsAreOff() {
		when(configuration.getProperty("ORDER_EMAIL_API")).thenReturn("false");

		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(orderService, never()).getById(any());
		verify(emails, never()).sendOrderEmail(anyString(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void sendsNothingForAMissingOrder() {
		when(orderService.getById(9L)).thenReturn(null);

		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(emails, never()).sendOrderEmail(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void aFailedSendDoesNotEscape() {
		when(orderService.getById(9L)).thenThrow(new IllegalStateException("database down"));

		listener.onPaid(new PendingOrderPaidEvent(9L));

		verify(emails, never()).sendOrderEmail(any(), any(), any(), any(), any(), any(), any());
	}
}
