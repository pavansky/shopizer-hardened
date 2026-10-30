package com.salesmanager.shop.store.facade.payment;

import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.modules.email.Email;
import com.salesmanager.core.business.services.merchant.MerchantStoreService;
import com.salesmanager.core.business.services.system.EmailService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.shop.constants.EmailConstants;
import com.salesmanager.shop.utils.DateUtil;
import com.salesmanager.shop.utils.EmailUtils;
import com.salesmanager.shop.utils.LabelUtils;

/**
 * Emails the store's address when its payment configuration changes, so an
 * unexpected credential change is noticed. Names the module, the user and the
 * changed settings, never their values.
 */
@Component
public class PaymentConfigurationChangeNotifier {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentConfigurationChangeNotifier.class);

	@Autowired
	private EmailService emailService;

	@Autowired
	private EmailUtils emailUtils;

	@Autowired
	private LabelUtils messages;

	@Autowired
	private MerchantStoreService merchantStoreService;

	/**
	 * Runs on the async pool. The store is reloaded by code in this thread rather than
	 * using the request thread's entity. Failures are logged; the change is already
	 * saved and audited.
	 */
	@Async
	@Transactional(readOnly = true)
	public void paymentConfigurationChanged(String storeCode, String moduleCode, String actor,
			Collection<String> changedSettings) {
		try {
			MerchantStore store = merchantStoreService.getByCode(storeCode);
			if (store == null || StringUtils.isBlank(store.getStoreEmailAddress())) {
				LOGGER.warn("Payment configuration change email skipped: store {} has no email address", storeCode);
				return;
			}
			emailService.sendHtmlEmail(store, email(store, moduleCode, actor, changedSettings, new Date()));
		} catch (Exception e) {
			LOGGER.error("Could not send the payment configuration change email for store " + storeCode, e);
		}
	}

	Email email(MerchantStore store, String moduleCode, String actor, Collection<String> changedSettings, Date when) {
		Locale locale = Constants.DEFAULT_LOCALE;
		Map<String, String> tokens = emailUtils.createEmailObjectsMap("", store, messages, locale);
		tokens.put(EmailConstants.LABEL_HI, messages.getMessage("label.generic.hi", locale));
		tokens.put(EmailConstants.EMAIL_CUSTOMER_FIRSTNAME, HtmlUtils.htmlEscape(store.getStorename()));
		tokens.put(EmailConstants.EMAIL_CUSTOMER_LASTNAME, "");
		tokens.put(EmailConstants.EMAIL_NOTIFICATION_MESSAGE, message(moduleCode, actor, changedSettings, when));

		Email email = new Email();
		email.setFrom(store.getStorename());
		email.setFromEmail(store.getStoreEmailAddress());
		email.setTo(store.getStoreEmailAddress());
		email.setSubject("Payment configuration changed: " + moduleCode);
		email.setTemplateName(EmailConstants.EMAIL_NOTIFICATION_TMPL);
		email.setTemplateTokens(tokens);
		return email;
	}

	static String message(String moduleCode, String actor, Collection<String> changedSettings, Date when) {
		return "The payment module <b>" + HtmlUtils.htmlEscape(moduleCode) + "</b> was changed by "
				+ HtmlUtils.htmlEscape(actor) + " on " + DateUtil.formatLongDate(when) + ".<br/>"
				+ "Changed settings: " + HtmlUtils.htmlEscape(String.join(", ", changedSettings)) + ".<br/>"
				+ "If you did not expect this change, rotate the gateway credentials and review your administrator accounts.";
	}
}
