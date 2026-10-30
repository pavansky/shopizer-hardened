package com.salesmanager.shop.store.controller.order.facade;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderPaidEvent;
import com.salesmanager.core.business.utils.CoreConfiguration;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.shop.utils.EmailTemplatesUtils;
import com.salesmanager.shop.utils.LocaleUtils;

/**
 * Sends the order confirmation for an order the reconciler settled after the
 * checkout request ended in PAYMENT_PENDING. The storefront's pending page
 * promises this email; the checkout itself never sent it. Same recipients and
 * gate (ORDER_EMAIL_API) as the confirmation sent at checkout.
 */
@Component
public class PendingOrderConfirmation {

	private static final Logger LOGGER = LoggerFactory.getLogger(PendingOrderConfirmation.class);

	@Inject
	private OrderService orderService;

	@Inject
	private CustomerService customerService;

	@Inject
	private EmailTemplatesUtils emailTemplatesUtils;

	@Inject
	private CoreConfiguration coreConfiguration;

	@EventListener
	public void onPaid(PendingOrderPaidEvent event) {
		if (!"true".equals(coreConfiguration.getProperty("ORDER_EMAIL_API"))) {
			return;
		}
		try {
			Order order = orderService.getById(event.getOrderId());
			if (order == null || order.getMerchant() == null) {
				LOGGER.warn("Confirmation for settled order {} skipped: order not found", event.getOrderId());
				return;
			}
			MerchantStore store = order.getMerchant();
			Customer customer = order.getCustomerId() == null ? null : customerService.getById(order.getCustomerId());
			String to = StringUtils.isNotBlank(order.getCustomerEmailAddress()) ? order.getCustomerEmailAddress()
					: customer == null ? null : customer.getEmailAddress();
			String contextPath = coreConfiguration.getProperty("CONTEXT_PATH");
			if (StringUtils.isNotBlank(to)) {
				emailTemplatesUtils.sendOrderEmail(to, customer, order, LocaleUtils.getLocale(store),
						store.getDefaultLanguage(), store, contextPath);
			}
			if (StringUtils.isNotBlank(store.getStoreEmailAddress())) {
				emailTemplatesUtils.sendOrderEmail(store.getStoreEmailAddress(), customer, order,
						LocaleUtils.getLocale(store), store.getDefaultLanguage(), store, contextPath);
			}
		} catch (RuntimeException e) {
			LOGGER.error("Confirmation for settled order " + event.getOrderId() + " not sent", e);
		}
	}
}
