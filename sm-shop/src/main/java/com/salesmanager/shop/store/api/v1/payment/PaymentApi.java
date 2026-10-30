package com.salesmanager.shop.store.api.v1.payment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.model.system.IntegrationModule;
import com.salesmanager.shop.model.system.IntegrationModuleConfiguration;
import com.salesmanager.shop.model.system.IntegrationModuleSummaryEntity;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.store.api.exception.ErrorEntity;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.exception.ServiceRuntimeException;
import com.salesmanager.shop.store.api.exception.UnauthorizedException;
import com.salesmanager.shop.store.facade.payment.PaymentConfigurationChangeNotifier;
import com.salesmanager.shop.store.facade.payment.PaymentModuleConfigurationFacade;
import com.salesmanager.shop.utils.AuthorizationUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.SwaggerDefinition;
import io.swagger.annotations.Tag;
import springfox.documentation.annotations.ApiIgnore;

/**
 * This API is for payment modules configurations. For payment of orders see
 * order
 * 
 * @author carlsamson
 *
 */
@RestController
@RequestMapping(value = "/api/v1")
@Api(tags = { "Payment api" })
@SwaggerDefinition(tags = { @Tag(name = "Payment management resources", description = "Payment management resources") })
public class PaymentApi {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentApi.class);

	private static final List<String> ADMIN_GROUPS = Arrays.asList(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN);

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentModuleConfigurationFacade paymentModuleConfigurationFacade;

	@Autowired
	private PaymentConfigurationChangeNotifier changeNotifier;

	@Autowired
	private AuthorizationUtils authorizationUtils;

	/**
	 * Get available payment modules
	 * 
	 * @param merchantStore
	 * @param language
	 * @return
	 */
	@GetMapping("/private/modules/payment")
	@ApiOperation(httpMethod = "GET", value = "List list of payment modules", notes = "Requires ADMIN or SUPERADMIN on the store", produces = "application/json", response = List.class)
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT") })
	public List<IntegrationModuleSummaryEntity> paymentModules(
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		authorizeStoreAdmin(merchantStore);
		try {
			List<IntegrationModule> modules = paymentService.getPaymentMethods(merchantStore);

			// configured modules
			Map<String, IntegrationConfiguration> configuredModules = paymentService
					.getPaymentModulesConfigured(merchantStore);
			return modules.stream().map(m -> integrationModule(m, configuredModules)).collect(Collectors.toList());

		} catch (ServiceException e) {
			LOGGER.error("Error getting payment modules", e);
			throw new ServiceRuntimeException("Error getting payment modules", e);
		}

	}

	@PostMapping(value = "/private/modules/payment")
	@ApiOperation(httpMethod = "POST", value = "Configure a payment module", notes = "Requires ADMIN or SUPERADMIN on the store."
			+ " A secret key sent back in its masked form (****1234) keeps the stored value.")
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT") })
	public void configure(
			@RequestBody IntegrationModuleConfiguration configuration,
			@ApiIgnore MerchantStore merchantStore) {

		String user = authorizeStoreAdmin(merchantStore);
		try {
			Set<String> changed = paymentModuleConfigurationFacade.save(configuration, merchantStore, user);
			if (!changed.isEmpty()) {
				LOGGER.info("Payment module [{}] of store [{}] changed by [{}]: {}", configuration.getCode(),
						merchantStore.getCode(), user, changed);
				changeNotifier.paymentConfigurationChanged(merchantStore.getCode(), configuration.getCode(), user,
						changed);
			}
		} catch (ServiceException e) {
			LOGGER.error("Error saving payment module", e);
			throw new ServiceRuntimeException("Error saving payment module", e);
		}

	}

	/**
	 * Get merchant payment module details. Secret keys are masked.
	 * 
	 * @param code
	 * @param merchantStore
	 * @param language
	 * @return
	 */
	@GetMapping("/private/modules/payment/{code}")
	@ApiOperation(httpMethod = "GET", value = "Payment module by code", notes = "Requires ADMIN or SUPERADMIN on the store."
			+ " Secret keys are returned masked.", produces = "application/json", response = List.class)
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT") })
	public IntegrationModuleConfiguration paymentModule(@PathVariable String code,
			@ApiIgnore MerchantStore merchantStore, @ApiIgnore Language language) {

		authorizeStoreAdmin(merchantStore);
		try {
			return paymentModuleConfigurationFacade.read(code, merchantStore);
		} catch (ServiceException e) {
			LOGGER.error("Error getting payment module [" + code + "]", e);
			throw new ServiceRuntimeException("Error getting payment module [" + code + "]", e);
		}

	}

	/**
	 * Payment configuration holds gateway credentials, so only store administrators
	 * may read or change it, and only for their own store (SUPERADMIN for any store).
	 * A user that no longer exists (deleted while the token is still valid) is a denial
	 * too, so it is reported as 403. Any other failure during the check, such as the
	 * database being unavailable, is left to propagate as a server error so outages are
	 * not hidden behind a 403.
	 */
	private String authorizeStoreAdmin(MerchantStore merchantStore) {
		String user = authorizationUtils.authenticatedUser();
		try {
			authorizationUtils.authorizeUser(user, ADMIN_GROUPS, merchantStore);
		} catch (ResourceNotFoundException e) {
			LOGGER.warn("Payment configuration authorization failed for [{}]: {}", user, e.getErrorMessage());
			throw new UnauthorizedException("Cannot authorize user [" + user + "] for store ["
					+ (merchantStore == null ? null : merchantStore.getCode()) + "]");
		}
		return user;
	}

	/**
	 * These endpoints are only reachable by authenticated users (/private), so a failed
	 * group or store check here, including the store check made while resolving the
	 * store parameter, is a 403 and not a 401.
	 */
	@ExceptionHandler(UnauthorizedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody ErrorEntity forbidden(UnauthorizedException exception) {
		LOGGER.warn("Payment configuration access denied: {}", exception.getErrorMessage());
		ErrorEntity errorEntity = new ErrorEntity();
		errorEntity.setErrorCode("403");
		errorEntity.setMessage("Not allowed to access the payment configuration of this store");
		return errorEntity;
	}

	private IntegrationModuleSummaryEntity integrationModule(IntegrationModule module,
			Map<String, IntegrationConfiguration> configuredModules) {

		IntegrationModuleSummaryEntity readable = null;
		readable = new IntegrationModuleSummaryEntity();

		readable.setCode(module.getCode());
		readable.setImage(module.getImage());
		readable.setBinaryImage(module.getBinaryImage());
		//readable.setRequiredKeys(module.getConfigurables());
		readable.setConfigurable(module.getConfigurable());
		if (configuredModules.containsKey(module.getCode())) {
			readable.setConfigured(true);
			if(configuredModules.get(module.getCode()).isActive()) {
				readable.setActive(true);
			}
		}
		return readable;

	}

}
