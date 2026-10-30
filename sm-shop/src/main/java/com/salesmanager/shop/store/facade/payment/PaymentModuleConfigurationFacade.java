package com.salesmanager.shop.store.facade.payment;

import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.system.audit.ConfigurationAuditService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.model.system.IntegrationModule;
import com.salesmanager.core.model.system.audit.ConfigurationAuditEntry;
import com.salesmanager.shop.model.system.IntegrationModuleConfiguration;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;

/**
 * Reads and writes a store's payment module configuration with secrets masked on
 * read, masked values kept on write, and every change audited.
 */
@Service
public class PaymentModuleConfigurationFacade {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private ConfigurationAuditService configurationAuditService;

	/** The module's configuration with secret values masked. */
	public IntegrationModuleConfiguration read(String code, MerchantStore store) throws ServiceException {
		IntegrationModule integrationModule = paymentService.getPaymentMethodByCode(store, code);
		if (integrationModule == null) {
			throw new ResourceNotFoundException("Payment module [" + code + "] not found");
		}

		IntegrationModuleConfiguration returnConfig = new IntegrationModuleConfiguration();
		returnConfig.setConfigurable(integrationModule.getConfigurable());
		returnConfig.setActive(false);
		returnConfig.setDefaultSelected(false);
		returnConfig.setCode(code);

		IntegrationConfiguration config = paymentService.getPaymentConfiguration(code, store);
		if (config == null) {
			return returnConfig;
		}
		returnConfig.setActive(config.isActive());
		returnConfig.setDefaultSelected(config.isDefaultSelected());
		returnConfig.setIntegrationKeys(PaymentConfigurationSecrets.maskKeys(config.getIntegrationKeys()));
		returnConfig.setIntegrationOptions(config.getIntegrationOptions());
		return returnConfig;
	}

	/**
	 * Saves the configuration and, when anything changed, writes an audit row in the
	 * same transaction. Returns the names of the settings that changed (empty when
	 * nothing did).
	 */
	@Transactional(rollbackFor = Exception.class)
	public Set<String> save(IntegrationModuleConfiguration configuration, MerchantStore store, String actor)
			throws ServiceException {
		boolean known = paymentService.getPaymentMethods(store).stream()
				.anyMatch(m -> m.getCode().equals(configuration.getCode()));
		if (!known) {
			throw new ResourceNotFoundException("Payment module [" + configuration.getCode() + "] not found");
		}

		Map<String, IntegrationConfiguration> configuredModules = paymentService.getPaymentModulesConfigured(store);
		IntegrationConfiguration integrationConfiguration = configuredModules.get(configuration.getCode());
		IntegrationConfiguration previous = PaymentConfigurationSecrets.snapshot(integrationConfiguration);
		if (integrationConfiguration == null) {
			integrationConfiguration = new IntegrationConfiguration();
			integrationConfiguration.setModuleCode(configuration.getCode());
		}

		integrationConfiguration.setActive(configuration.isActive());
		integrationConfiguration.setDefaultSelected(configuration.isDefaultSelected());
		integrationConfiguration.setIntegrationKeys(PaymentConfigurationSecrets.mergeKeys(
				configuration.getIntegrationKeys(), previous == null ? null : previous.getIntegrationKeys()));
		integrationConfiguration.setIntegrationOptions(configuration.getIntegrationOptions());

		paymentService.savePaymentModuleConfiguration(integrationConfiguration, store);

		Set<String> changed = PaymentConfigurationSecrets.changedSettings(previous, integrationConfiguration);
		if (!changed.isEmpty()) {
			configurationAuditService.record(store, ConfigurationAuditEntry.TYPE_PAYMENT, configuration.getCode(),
					actor, changed);
		}
		return changed;
	}
}
