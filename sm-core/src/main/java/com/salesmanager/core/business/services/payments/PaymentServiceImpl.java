package com.salesmanager.core.business.services.payments;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.modules.integration.payment.impl.MoneyOrderPayment;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.order.OrderStatusTransitions;
import com.salesmanager.core.business.services.reference.loader.ConfigurationModulesLoader;
import com.salesmanager.core.business.services.system.MerchantConfigurationService;
import com.salesmanager.core.business.services.system.ModuleConfigurationService;
import com.salesmanager.core.business.utils.CoreConfiguration;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.CreditCardPayment;
import com.salesmanager.core.model.payments.CreditCardType;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentMethod;
import com.salesmanager.core.model.payments.PaymentType;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.model.system.IntegrationModule;
import com.salesmanager.core.model.system.MerchantConfiguration;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;
import com.salesmanager.core.modules.utils.Encryption;


@Service("paymentService")
public class PaymentServiceImpl implements PaymentService {

	@javax.persistence.PersistenceContext
	private javax.persistence.EntityManager em;

	/**
	 * Serialises capture and refund per order. Taken as the first statement of the
	 * transaction, so a concurrent request waits here and then reads the ledger as
	 * committed by the first one; two refunds can no longer both pass the
	 * remaining-amount check.
	 */
	void lockOrder(Order order) {
		if (em != null && order.getId() != null) {
			em.find(Order.class, order.getId(), javax.persistence.LockModeType.PESSIMISTIC_WRITE);
		}
	}
	
	
	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentServiceImpl.class);
	

	@Inject
	private MerchantConfigurationService merchantConfigurationService;
	
	@Inject
	private ModuleConfigurationService moduleConfigurationService;
	
	@Inject
	private TransactionService transactionService;
	
	@Inject
	private OrderService orderService;
	
	@Inject
	private CoreConfiguration coreConfiguration;
	
	@Inject
	@Resource(name="paymentModules")
	private Map<String,PaymentModule> paymentModules;
	
	@Inject
	private Encryption encryption;

	/**
	 * Gateway calls allowed at the same time per payment module on this instance.
	 * Kept below the database pool (15 connections): with open-in-view a checkout
	 * waiting on the gateway still holds the connection it used to reserve stock,
	 * so a slow gateway must not be able to take every connection and stop browsing.
	 */
	@Value("${config.payment.bulkhead.maxConcurrent:10}")
	private int bulkheadPermits = 10;

	/** Seconds a client is told to wait before retrying when the bulkhead is full. */
	@Value("${config.payment.bulkhead.retryAfterSeconds:2}")
	private int bulkheadRetryAfterSeconds = 2;

	private final ConcurrentMap<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

	/** One call into a payment module. */
	@FunctionalInterface
	interface GatewayCall<T> {
		T call() throws ServiceException;
	}

	/**
	 * Runs a payment module call inside that module's bulkhead. When every permit is
	 * taken the call fails at once with PaymentGatewayBusyException, before the module
	 * runs, so a rejected call never reaches the gateway. A permit is only released by
	 * the call that took it, after the module returned or threw. The money order module
	 * makes no network call and is not limited.
	 */
	<T> T callGateway(String moduleCode, PaymentModule module, GatewayCall<T> call) throws ServiceException {
		if (module instanceof MoneyOrderPayment) {
			return call.call();
		}
		Semaphore permits = bulkheads.computeIfAbsent(moduleCode, code -> new Semaphore(bulkheadPermits));
		if (!permits.tryAcquire()) {
			LOGGER.warn("Payment gateway {} bulkhead full ({} calls in flight); call rejected before reaching the gateway",
					moduleCode, bulkheadPermits);
			throw new PaymentGatewayBusyException(moduleCode, bulkheadRetryAfterSeconds);
		}
		try {
			return call.call();
		} finally {
			permits.release();
		}
	}
	
	@Override
	public List<IntegrationModule> getPaymentMethods(MerchantStore store) throws ServiceException {
		
		List<IntegrationModule> modules =  moduleConfigurationService.getIntegrationModules(Constants.PAYMENT_MODULES);
		List<IntegrationModule> returnModules = new ArrayList<IntegrationModule>();
		
		for(IntegrationModule module : modules) {
			if(module.getRegionsSet().contains(store.getCountry().getIsoCode())
					|| module.getRegionsSet().contains("*")) {
				
				returnModules.add(module);
			}
		}
		
		return returnModules;
	}
	
	@Override
	public List<PaymentMethod> getAcceptedPaymentMethods(MerchantStore store) throws ServiceException {
		
		Map<String,IntegrationConfiguration> modules =  this.getPaymentModulesConfigured(store);

		List<PaymentMethod> returnModules = new ArrayList<PaymentMethod>();
		
		for(String module : modules.keySet()) {
			IntegrationConfiguration config = modules.get(module);
			if(config.isActive()) {
				
				IntegrationModule md = this.getPaymentMethodByCode(store, config.getModuleCode());
				if(md==null) {
					continue;
				}
				PaymentMethod paymentMethod = new PaymentMethod();
				
				paymentMethod.setDefaultSelected(config.isDefaultSelected());
				paymentMethod.setPaymentMethodCode(config.getModuleCode());
				paymentMethod.setModule(md);
				paymentMethod.setInformations(config);

				PaymentType type = PaymentType.fromString(md.getType());

				paymentMethod.setPaymentType(type);
				returnModules.add(paymentMethod);
			}
		}
		
		return returnModules;
		
		
	}
	
	@Override
	public IntegrationModule getPaymentMethodByType(MerchantStore store, String type) throws ServiceException {
		List<IntegrationModule> modules =  getPaymentMethods(store);

		for(IntegrationModule module : modules) {
			if(module.getModule().equals(type)) {
				
				return module;
			}
		}
		
		return null;
	}
	
	@Override
	public IntegrationModule getPaymentMethodByCode(MerchantStore store,
			String code) throws ServiceException {
		List<IntegrationModule> modules =  getPaymentMethods(store);

		for(IntegrationModule module : modules) {
			if(module.getCode().equals(code)) {
				
				return module;
			}
		}
		
		return null;
	}
	
	@Override
	public IntegrationConfiguration getPaymentConfiguration(String moduleCode, MerchantStore store) throws ServiceException {

		Validate.notNull(moduleCode,"Module code must not be null");
		Validate.notNull(store,"Store must not be null");
		
		String mod = moduleCode.toLowerCase();
		
		Map<String,IntegrationConfiguration> configuredModules = getPaymentModulesConfigured(store);
		if(configuredModules!=null) {
			for(String key : configuredModules.keySet()) {
				if(key.equals(mod)) {
					return configuredModules.get(key);	
				}
			}
		}
		
		return null;
		
	}
	

	
	@Override
	public Map<String,IntegrationConfiguration> getPaymentModulesConfigured(MerchantStore store) throws ServiceException {
		
		try {
		
			Map<String,IntegrationConfiguration> modules = new HashMap<String,IntegrationConfiguration>();
			MerchantConfiguration merchantConfiguration = merchantConfigurationService.getMerchantConfiguration(Constants.PAYMENT_MODULES, store);
			if(merchantConfiguration!=null) {
				
				if(!StringUtils.isBlank(merchantConfiguration.getValue())) {
					
					String decrypted = encryption.decrypt(merchantConfiguration.getValue());
					modules = ConfigurationModulesLoader.loadIntegrationConfigurations(decrypted);
					
					
				}
			}
			return modules;
		
		} catch (Exception e) {
			throw new ServiceException(e);
		}
	}
	
	@Override
	public void savePaymentModuleConfiguration(IntegrationConfiguration configuration, MerchantStore store) throws ServiceException {
		
		//validate entries
		try {
			
			String moduleCode = configuration.getModuleCode();
			PaymentModule module = paymentModules.get(moduleCode);
			if(module==null) {
				throw new ServiceException("Payment module " + moduleCode + " does not exist");
			}
			module.validateModuleConfiguration(configuration, store);
			
		} catch (IntegrationException ie) {
			throw ie;
		}
		
		try {
			Map<String,IntegrationConfiguration> modules = new HashMap<String,IntegrationConfiguration>();
			MerchantConfiguration merchantConfiguration = merchantConfigurationService.getMerchantConfiguration(Constants.PAYMENT_MODULES, store);
			if(merchantConfiguration!=null) {
				if(!StringUtils.isBlank(merchantConfiguration.getValue())) {
					
					String decrypted = encryption.decrypt(merchantConfiguration.getValue());
					
					modules = ConfigurationModulesLoader.loadIntegrationConfigurations(decrypted);
				}
			} else {
				merchantConfiguration = new MerchantConfiguration();
				merchantConfiguration.setMerchantStore(store);
				merchantConfiguration.setKey(Constants.PAYMENT_MODULES);
			}
			modules.put(configuration.getModuleCode(), configuration);
			
			String configs =  ConfigurationModulesLoader.toJSONString(modules);
			
			String encrypted = encryption.encrypt(configs);
			merchantConfiguration.setValue(encrypted);
			
			merchantConfigurationService.saveOrUpdate(merchantConfiguration);
			
		} catch (Exception e) {
			throw new ServiceException(e);
		}
   }
	
	@Override
	public void removePaymentModuleConfiguration(String moduleCode, MerchantStore store) throws ServiceException {
		
		

		try {
			Map<String,IntegrationConfiguration> modules = new HashMap<String,IntegrationConfiguration>();
			MerchantConfiguration merchantConfiguration = merchantConfigurationService.getMerchantConfiguration(Constants.PAYMENT_MODULES, store);
			if(merchantConfiguration!=null) {

				if(!StringUtils.isBlank(merchantConfiguration.getValue())) {
					
					String decrypted = encryption.decrypt(merchantConfiguration.getValue());
					modules = ConfigurationModulesLoader.loadIntegrationConfigurations(decrypted);
				}
				
				modules.remove(moduleCode);
				String configs =  ConfigurationModulesLoader.toJSONString(modules);
				
				String encrypted = encryption.encrypt(configs);
				merchantConfiguration.setValue(encrypted);
				
				merchantConfigurationService.saveOrUpdate(merchantConfiguration);
				
				
			} 
			
			MerchantConfiguration configuration = merchantConfigurationService.getMerchantConfiguration(moduleCode, store);
			
			if(configuration!=null) {//custom module

				merchantConfigurationService.delete(configuration);
			}

			
		} catch (Exception e) {
			throw new ServiceException(e);
		}
	
	}
	

	


	@Override
	public Transaction processPayment(Customer customer,
			MerchantStore store, Payment payment, List<ShoppingCartItem> items, Order order)
			throws ServiceException {


		Validate.notNull(customer);
		Validate.notNull(store);
		Validate.notNull(payment);
		Validate.notNull(order);
		Validate.notNull(order.getTotal());
		
		payment.setCurrency(store.getCurrency());
		
		BigDecimal amount = order.getTotal();

		//must have a shipping module configured
		Map<String, IntegrationConfiguration> modules = this.getPaymentModulesConfigured(store);
		if(modules==null){
			throw new ServiceException("No payment module configured");
		}
		
		IntegrationConfiguration configuration = modules.get(payment.getModuleName());
		
		if(configuration==null) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " is not configured");
		}
		
		if(!configuration.isActive()) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " is not active");
		}
		
		TransactionType configuredType = resolveTransactionType(configuration.getIntegrationKeys().get("transaction"));
		String sTransactionType = configuredType.name();

		if(configuredType == TransactionType.AUTHORIZE) {
			payment.setTransactionType(TransactionType.AUTHORIZE);
		} else {
			payment.setTransactionType(TransactionType.AUTHORIZECAPTURE);
		}

		PaymentModule module = this.paymentModules.get(payment.getModuleName());
		
		if(module==null) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " does not exist");
		}
		
		if(payment instanceof CreditCardPayment && "true".equals(coreConfiguration.getProperty("VALIDATE_CREDIT_CARD"))) {
			CreditCardPayment creditCardPayment = (CreditCardPayment)payment;
			validateCreditCard(creditCardPayment.getCreditCardNumber(),creditCardPayment.getCreditCard(),creditCardPayment.getExpirationMonth(),creditCardPayment.getExpirationYear());
		}
		
		IntegrationModule integrationModule = getPaymentMethodByCode(store,payment.getModuleName());
		TransactionType transactionType = TransactionType.valueOf(sTransactionType);
		if(transactionType==null) {
			transactionType = payment.getTransactionType();
			if(transactionType.equals(TransactionType.CAPTURE.name())) {
				throw new ServiceException("This method does not allow to process capture transaction. Use processCapturePayment");
			}
		}
		
		Transaction transaction = null;
		String moduleCode = payment.getModuleName();
		if(transactionType == TransactionType.AUTHORIZE)  {
			transaction = callGateway(moduleCode, module,
					() -> module.authorize(store, customer, items, amount, payment, configuration, integrationModule));
		} else if(transactionType == TransactionType.AUTHORIZECAPTURE)  {
			transaction = callGateway(moduleCode, module,
					() -> module.authorizeAndCapture(store, customer, items, amount, payment, configuration, integrationModule));
		} else if(transactionType == TransactionType.INIT)  {
			transaction = callGateway(moduleCode, module,
					() -> module.initTransaction(store, customer, amount, payment, configuration, integrationModule));
		}


		if(transactionType != TransactionType.INIT) {
			transactionService.create(transaction);
		}
		
		if(transactionType == TransactionType.AUTHORIZECAPTURE)  {
			order.setStatus(OrderStatus.ORDERED);
			if(!payment.getPaymentType().name().equals(PaymentType.MONEYORDER.name())) {
				order.setStatus(OrderStatus.PROCESSED);
			}
		}

		return transaction;

		

	}
	
	@Override
	public PaymentModule getPaymentModule(String paymentModuleCode) throws ServiceException {
		return paymentModules.get(paymentModuleCode);
	}
	
	/**
	 * Maps the "transaction" key of a payment module configuration to a
	 * TransactionType. The legacy admin form posts "SALE", which means
	 * authorize and capture. Missing values default to AUTHORIZECAPTURE;
	 * unknown values also default to AUTHORIZECAPTURE and are logged.
	 */
	static TransactionType resolveTransactionType(String configured) {
		if (StringUtils.isBlank(configured)) {
			return TransactionType.AUTHORIZECAPTURE;
		}
		String value = configured.trim().toUpperCase();
		if ("SALE".equals(value)) {
			return TransactionType.AUTHORIZECAPTURE;
		}
		if (TransactionType.AUTHORIZE.name().equals(value) || TransactionType.AUTHORIZECAPTURE.name().equals(value)) {
			return TransactionType.valueOf(value);
		}
		LOGGER.warn("Unknown payment transaction type [{}] in module configuration, using AUTHORIZECAPTURE", configured);
		return TransactionType.AUTHORIZECAPTURE;
	}

	@Override
	// One transaction: the order row lock, the gateway call and the ledger entry commit together.
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
	public Transaction processCapturePayment(Order order, Customer customer,
			MerchantStore store)
			throws ServiceException {

		Validate.notNull(customer);
		Validate.notNull(store);
		Validate.notNull(order);
		lockOrder(order);

		IntegrationConfiguration configuration = activeConfiguration(store, order.getPaymentModuleCode());
		PaymentModule module = paymentModule(order.getPaymentModuleCode());
		IntegrationModule integrationModule = getPaymentMethodByCode(store,order.getPaymentModuleCode());

		Transaction trx = transactionService.getCapturableTransaction(order);
		if(trx==null) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,
					"No capturable transaction for order id " + order.getId(), "message.payment.error");
		}
		Transaction transaction = callGateway(order.getPaymentModuleCode(), module,
				() -> module.capture(store, customer, order, trx, configuration, integrationModule));
		if(transaction == null) {
			throw new ServiceException("Payment module " + order.getPaymentModuleCode() + " returned no capture transaction");
		}
		transaction.setOrder(order);
		transactionService.create(transaction);

		changeStatus(order, OrderStatus.PROCESSED, "Payment captured, amount " + transaction.getAmount());

		return transaction;
	}

	/**
	 * Refunds the given amount against the latest captured transaction.
	 * The amount must be positive and not exceed captured minus already
	 * refunded. A refund that brings the remaining amount to zero moves the
	 * order to REFUNDED; a partial refund only adds a history comment. The
	 * order total is left untouched; refunds are tracked as REFUND
	 * transactions in the ledger.
	 */
	@Override
	// One transaction: the order row lock, the gateway call and the ledger entry commit together.
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
	public Transaction processRefund(Order order, Customer customer,
			MerchantStore store, BigDecimal amount)
			throws ServiceException {

		Validate.notNull(store);
		Validate.notNull(amount);
		Validate.notNull(order);
		lockOrder(order);

		Transaction refundable = transactionService.getRefundableTransaction(order);
		if(refundable==null) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,
					"No refundable transaction for order id " + order.getId(), "message.payment.error");
		}

		BigDecimal remaining = transactionService.getRefundableAmount(order);
		if(amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,
					"Invalid refund amount " + amount + ", refundable amount is " + remaining, "message.payment.error");
		}

		String moduleCode = order.getPaymentModuleCode();
		IntegrationConfiguration configuration = activeConfiguration(store, moduleCode);
		PaymentModule paymentModule = paymentModule(moduleCode);
		IntegrationModule integrationModule = getPaymentMethodByCode(store,moduleCode);

		boolean partial = amount.compareTo(remaining) < 0;

		Transaction transaction = callGateway(moduleCode, paymentModule,
				() -> paymentModule.refund(partial, store, refundable, order, amount, configuration, integrationModule));
		if(transaction == null) {
			throw new ServiceException("Payment module " + moduleCode + " returned no refund transaction");
		}
		// the ledger entry always reflects what was refunded, whatever the module returned
		transaction.setTransactionType(TransactionType.REFUND);
		transaction.setAmount(amount);
		transaction.setOrder(order);
		transactionService.create(transaction);

		if(partial) {
			BigDecimal left = remaining.subtract(amount);
			addHistory(order, order.getStatus(), "Partial refund of " + amount + ", remaining refundable " + left);
			orderService.saveOrUpdate(order);
		} else {
			changeStatus(order, OrderStatus.REFUNDED, "Refund of " + amount);
		}

		return transaction;
	}

	private IntegrationConfiguration activeConfiguration(MerchantStore store, String moduleCode) throws ServiceException {
		Map<String, IntegrationConfiguration> modules = this.getPaymentModulesConfigured(store);
		if(modules==null){
			throw new ServiceException("No payment module configured");
		}
		IntegrationConfiguration configuration = modules.get(moduleCode);
		if(configuration==null) {
			throw new ServiceException("Payment module " + moduleCode + " is not configured");
		}
		if(!configuration.isActive()) {
			throw new ServiceException("Payment module " + moduleCode + " is not active");
		}
		return configuration;
	}

	private PaymentModule paymentModule(String moduleCode) throws ServiceException {
		PaymentModule module = this.paymentModules.get(moduleCode);
		if(module==null) {
			throw new ServiceException("Payment module " + moduleCode + " does not exist");
		}
		return module;
	}

	/** Applies a status change through the transition table and records it in the order history. */
	private void changeStatus(Order order, OrderStatus target, String comment) throws ServiceException {
		if(order.getStatus() != target) {
			OrderStatusTransitions.check(order.getStatus(), target);
			order.setStatus(target);
		}
		addHistory(order, target, comment);
		orderService.saveOrUpdate(order);
	}

	private void addHistory(Order order, OrderStatus status, String comment) {
		OrderStatusHistory orderHistory = new OrderStatusHistory();
		orderHistory.setOrder(order);
		orderHistory.setStatus(status);
		orderHistory.setComments(comment);
		orderHistory.setDateAdded(new Date());
		order.getOrderHistory().add(orderHistory);
	}

	@Override
	public void validateCreditCard(String number, CreditCardType creditCard, String month, String date)
	throws ServiceException {

		try {
			Integer.parseInt(month);
			Integer.parseInt(date);
		} catch (NumberFormatException nfe) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid date format","messages.error.creditcard.dateformat");
		}
		
		if (StringUtils.isBlank(number)) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
		}
		
		Matcher m = Pattern.compile("[^\\d\\s.-]").matcher(number);
		
		if (m.find()) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
		}
		
		Matcher matcher = Pattern.compile("[\\s.-]").matcher(number);
		
		number = matcher.replaceAll("");
		validateCreditCardDate(Integer.parseInt(month), Integer.parseInt(date));
		validateCreditCardNumber(number, creditCard);
	}

	private void validateCreditCardDate(int m, int y) throws ServiceException {
		java.util.Calendar cal = new java.util.GregorianCalendar();
		int monthNow = cal.get(java.util.Calendar.MONTH) + 1;
		int yearNow = cal.get(java.util.Calendar.YEAR);
		if (yearNow > y) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid date format","messages.error.creditcard.dateformat");
		}
		// OK, change implementation
		if (yearNow == y && monthNow > m) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid date format","messages.error.creditcard.dateformat");
		}
	
	}
	
	@Deprecated
	/**
	 * Use commons validator CreditCardValidator
	 * @param number
	 * @param creditCard
	 * @throws ServiceException
	 */
	private void validateCreditCardNumber(String number, CreditCardType creditCard)
	throws ServiceException {

		//TODO implement
		if(CreditCardType.MASTERCARD.equals(creditCard.name())) {
			if (number.length() != 16
					|| Integer.parseInt(number.substring(0, 2)) < 51
					|| Integer.parseInt(number.substring(0, 2)) > 55) {
				throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
			}
		}
		
		if(CreditCardType.VISA.equals(creditCard.name())) {
			if ((number.length() != 13 && number.length() != 16)
					|| Integer.parseInt(number.substring(0, 1)) != 4) {
				throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
			}
		}
		
		if(CreditCardType.AMEX.equals(creditCard.name())) {
			if (number.length() != 15
					|| (Integer.parseInt(number.substring(0, 2)) != 34 && Integer
							.parseInt(number.substring(0, 2)) != 37)) {
				throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
			}
		}
		
		if(CreditCardType.DINERS.equals(creditCard.name())) {
			if (number.length() != 14
					|| ((Integer.parseInt(number.substring(0, 2)) != 36 && Integer
							.parseInt(number.substring(0, 2)) != 38)
							&& Integer.parseInt(number.substring(0, 3)) < 300 || Integer
							.parseInt(number.substring(0, 3)) > 305)) {
				throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
			}
		}
		
		if(CreditCardType.DISCOVERY.equals(creditCard.name())) {
			if (number.length() != 16
					|| Integer.parseInt(number.substring(0, 5)) != 6011) {
				throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
			}
		}

		luhnValidate(number);
	}

	// The Luhn algorithm is basically a CRC type
	// system for checking the validity of an entry.
	// All major credit cards use numbers that will
	// pass the Luhn check. Also, all of them are based
	// on MOD 10.
	@Deprecated
	private void luhnValidate(String numberString)
			throws ServiceException {
		char[] charArray = numberString.toCharArray();
		int[] number = new int[charArray.length];
		int total = 0;
	
		for (int i = 0; i < charArray.length; i++) {
			number[i] = Character.getNumericValue(charArray[i]);
		}
	
		for (int i = number.length - 2; i > -1; i -= 2) {
			number[i] *= 2;
	
			if (number[i] > 9)
				number[i] -= 9;
		}

		for (int j : number) {
			total += j;
		}
	
		if (total % 10 != 0) {
			throw new ServiceException(ServiceException.EXCEPTION_VALIDATION,"Invalid card number","messages.error.creditcard.number");
		}
	
	}

	@Override
	public Transaction initTransaction(Order order, Customer customer, Payment payment, MerchantStore store) throws ServiceException {
		
		Validate.notNull(store);
		Validate.notNull(payment);
		Validate.notNull(order);
		Validate.notNull(order.getTotal());
		
		payment.setCurrency(store.getCurrency());
		
		BigDecimal amount = order.getTotal();

		//must have a shipping module configured
		Map<String, IntegrationConfiguration> modules = this.getPaymentModulesConfigured(store);
		if(modules==null){
			throw new ServiceException("No payment module configured");
		}
		
		IntegrationConfiguration configuration = modules.get(payment.getModuleName());
		
		if(configuration==null) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " is not configured");
		}
		
		if(!configuration.isActive()) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " is not active");
		}
		
		PaymentModule module = this.paymentModules.get(order.getPaymentModuleCode());
		
		if(module==null) {
			throw new ServiceException("Payment module " + order.getPaymentModuleCode() + " does not exist");
		}
		
		IntegrationModule integrationModule = getPaymentMethodByCode(store,payment.getModuleName());

		return callGateway(order.getPaymentModuleCode(), module,
				() -> module.initTransaction(store, customer, amount, payment, configuration, integrationModule));
	}

	@Override
	public Transaction initTransaction(Customer customer, Payment payment, MerchantStore store) throws ServiceException {

		Validate.notNull(store);
		Validate.notNull(payment);
		Validate.notNull(payment.getAmount());
		
		payment.setCurrency(store.getCurrency());
		
		BigDecimal amount = payment.getAmount();

		//must have a shipping module configured
		Map<String, IntegrationConfiguration> modules = this.getPaymentModulesConfigured(store);
		if(modules==null){
			throw new ServiceException("No payment module configured");
		}
		
		IntegrationConfiguration configuration = modules.get(payment.getModuleName());
		
		if(configuration==null) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " is not configured");
		}
		
		if(!configuration.isActive()) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " is not active");
		}
		
		PaymentModule module = this.paymentModules.get(payment.getModuleName());
		
		if(module==null) {
			throw new ServiceException("Payment module " + payment.getModuleName() + " does not exist");
		}
		
		IntegrationModule integrationModule = getPaymentMethodByCode(store,payment.getModuleName());
		
		Transaction transaction = callGateway(payment.getModuleName(), module,
				() -> module.initTransaction(store, customer, amount, payment, configuration, integrationModule));
		
		transactionService.save(transaction);

		return transaction;
	}


	


}
