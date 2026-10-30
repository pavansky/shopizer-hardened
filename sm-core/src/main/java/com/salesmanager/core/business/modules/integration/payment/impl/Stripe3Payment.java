package com.salesmanager.core.business.modules.integration.payment.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentType;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.system.IntegrationConfiguration;
import com.salesmanager.core.model.system.IntegrationModule;
import com.salesmanager.core.modules.integration.IntegrationException;
import com.salesmanager.core.modules.integration.PaymentOutcomeUnknownException;
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.PaymentCompensation;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;
import com.salesmanager.core.modules.integration.payment.model.PaymentStatusLookup;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;

// import com.stripe.exception.APIConnectionException;

public class Stripe3Payment implements PaymentModule, PaymentStatusLookup, PaymentCompensation {

	private static final Logger LOGGER = LoggerFactory.getLogger(Stripe3Payment.class);

	private final static String AUTHORIZATION = "Authorization";
	private final static String TRANSACTION = "Transaction";

	/** Payment metadata key of the cart a PaymentIntent is created for; sent to Stripe as cart_code. */
	public static final String META_CART_CODE = "cartCode";


	@Override
	public Transaction initTransaction(MerchantStore store, Customer customer,
									   BigDecimal amount, Payment payment,
									   IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {


		Transaction transaction = new Transaction();
		try {

			long minorAmount = StripeAmounts.toMinorUnits(amount, store.getCurrency().getCode());


			String apiKey = configuration.getIntegrationKeys().get("secretKey");

			if (StringUtils.isBlank(apiKey)) {
				IntegrationException te = new IntegrationException(
						"Can't process Stripe, missing payment.metaData");
				te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
				te.setMessageCode("message.payment.error");
				te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
				throw te;
			}

			RequestOptions requestOptions = requestOptions(apiKey);

			// No redirect-based methods: the storefront confirms with redirect 'if_required' and
			// has no return page to resume from. Cards run 3-D Secure in place when the issuer asks.
			PaymentIntentCreateParams.Builder create = new PaymentIntentCreateParams.Builder()
					.setCurrency(store.getCurrency().getCode())
					.setAmount(minorAmount)
					.setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
					.setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
							.setEnabled(true)
							.setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
							.build());
			String cartCode = payment.getPaymentMetaData() == null ? null
					: payment.getPaymentMetaData().get(META_CART_CODE);
			if (StringUtils.isNotBlank(cartCode)) {
				create.putMetadata("cart_code", cartCode);
			}
			PaymentIntentCreateParams createParams = create.build();

			// Create a PaymentIntent with the order amount and currency
			PaymentIntent intent = createPaymentIntent(createParams, requestOptions);

			transaction.setAmount(amount);
			//transaction.setOrder(order);
			transaction.setTransactionDate(new Date());
			transaction.setTransactionType(TransactionType.AUTHORIZE);
			transaction.setPaymentType(PaymentType.CREDITCARD);
			transaction.getTransactionDetails().put("TRANSACTIONID", intent.getId());
			transaction.getTransactionDetails().put("TRNAPPROVED", intent.getStatus());
			transaction.getTransactionDetails().put("TRNORDERNUMBER", intent.getId());
			transaction.getTransactionDetails().put("INTENTSECRET", intent.getClientSecret());
			transaction.getTransactionDetails().put("MESSAGETEXT", null);

		} catch (Exception e) {
			throw buildException(e);
		}

		return transaction;
	}

	@Override
	public void validateModuleConfiguration(
			IntegrationConfiguration integrationConfiguration,
			MerchantStore store) throws IntegrationException {
		
		
		List<String> errorFields = null;
		
		
		Map<String,String> keys = integrationConfiguration.getIntegrationKeys();
		
		//validate integrationKeys['secretKey']
		if(keys==null || StringUtils.isBlank(keys.get("secretKey"))) {
			errorFields = new ArrayList<String>();
			errorFields.add("secretKey");
		}
		
		//validate integrationKeys['publishableKey']
		if(keys==null || StringUtils.isBlank(keys.get("publishableKey"))) {
			if(errorFields==null) {
				errorFields = new ArrayList<String>();
			}
			errorFields.add("publishableKey");
		}
		
		
		if(errorFields!=null) {
			IntegrationException ex = new IntegrationException(IntegrationException.ERROR_VALIDATION_SAVE);
			ex.setErrorFields(errorFields);
			throw ex;
		}
	}


	/* -----------------------------------------  */

	@Override
	public Transaction authorize(MerchantStore store, Customer customer,
			List<ShoppingCartItem> items, BigDecimal amount, Payment payment,
			IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {
		
		Transaction transaction = new Transaction();
		try {
			

			String apiKey = configuration.getIntegrationKeys().get("secretKey");

			if(payment.getPaymentMetaData()==null || StringUtils.isBlank(apiKey)) {
				IntegrationException te = new IntegrationException(
						"Can't process Stripe, missing payment.metaData");
				te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
				te.setMessageCode("message.payment.error");
				te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
				throw te;
			}
			
			/**
			 * This is the PaymentIntent ID created on the Frontend, that we will now store.
			 */
			String token = payment.getPaymentMetaData().get("stripe_token");
			
			if(StringUtils.isBlank(token)) {
				IntegrationException te = new IntegrationException(
						"Can't process Stripe, missing stripe token");
				te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
				te.setMessageCode("message.payment.error");
				te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
				throw te;
			}

			RequestOptions requestOptions = requestOptions(apiKey);
			
			PaymentIntent paymentIntent = PaymentIntent.retrieve(token, requestOptions);
			
			transaction.setAmount(amount);
			//transaction.setOrder(order);
			transaction.setTransactionDate(new Date());
			transaction.setTransactionType(TransactionType.AUTHORIZE);
			transaction.setPaymentType(PaymentType.CREDITCARD);
			transaction.getTransactionDetails().put("TRANSACTIONID", token);
			transaction.getTransactionDetails().put("TRNAPPROVED", paymentIntent.getStatus());
			transaction.getTransactionDetails().put("TRNORDERNUMBER", paymentIntent.getId());  // <---- We store the PI id here
			transaction.getTransactionDetails().put("MESSAGETEXT", null);
			
		} catch (Exception e) {
			
			throw buildException(e);

		} 
		
		return transaction;

		
	}

	@Override
	public Transaction capture(MerchantStore store, Customer customer,
			Order order, Transaction capturableTransaction,
			IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {

		Transaction transaction = new Transaction();
		try {

			String apiKey = configuration.getIntegrationKeys().get("secretKey");

			if(StringUtils.isBlank(apiKey)) {
				IntegrationException te = new IntegrationException(
						"Can't process Stripe, missing payment.metaData");
				te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
				te.setMessageCode("message.payment.error");
				te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
				throw te;
			}

			String chargeId = capturableTransaction.getTransactionDetails().get("TRNORDERNUMBER");       // <---- We retrieve the PI id here

			if(StringUtils.isBlank(chargeId)) {
				IntegrationException te = new IntegrationException(
						"Can't process Stripe capture, missing TRNORDERNUMBER");
				te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
				te.setMessageCode("message.payment.error");
				te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
				throw te;
			}

			long minorAmount = StripeAmounts.toMinorUnits(order.getTotal(), store.getCurrency().getCode());

			RequestOptions requestOptions = requestOptions(apiKey);


			PaymentIntent paymentIntent = PaymentIntent.retrieve(chargeId, requestOptions);

			checkCapturable(paymentIntent, minorAmount);

			PaymentIntentCaptureParams params =
					PaymentIntentCaptureParams.builder()
							.setAmountToCapture(minorAmount)
							.setStatementDescriptorSuffix(statementSuffix(store.getStorename()))
					.build();

			paymentIntent = paymentIntent.capture(params, requestOptions);

			transaction.setAmount(order.getTotal());
			transaction.setOrder(order);
			transaction.setTransactionDate(new Date());
			transaction.setTransactionType(TransactionType.CAPTURE);
			transaction.setPaymentType(PaymentType.CREDITCARD);
			transaction.getTransactionDetails().put("TRANSACTIONID", capturableTransaction.getTransactionDetails().get("TRANSACTIONID"));
			transaction.getTransactionDetails().put("TRNAPPROVED", paymentIntent.getStatus());
			transaction.getTransactionDetails().put("TRNORDERNUMBER", paymentIntent.getId());
			transaction.getTransactionDetails().put("MESSAGETEXT", null);

			return transaction;

		} catch (Exception e) {
			throw buildException(e);
		}
	}

	@Override
	public Transaction authorizeAndCapture(MerchantStore store, Customer customer,
			List<ShoppingCartItem> items, BigDecimal amount, Payment payment,
			IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {
		
		String apiKey = configuration.getIntegrationKeys().get("secretKey");

		if(payment.getPaymentMetaData()==null || StringUtils.isBlank(apiKey)) {
			IntegrationException te = new IntegrationException(
					"Can't process Stripe, missing payment.metaData");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
		}
		
		String token = payment.getPaymentMetaData().get("stripe_token");
		if(StringUtils.isBlank(token)) { //possibly from api
		  token = payment.getPaymentMetaData().get("paymentToken");
		}
		
		if(StringUtils.isBlank(token)) {
			IntegrationException te = new IntegrationException(
					"Can't process Stripe, missing stripe token");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
		}
		


		Transaction transaction = new Transaction();
		try {
			
			//stripe expects an integer amount in the currency minor unit
			//553.47 USD = 55347
			long minorAmount = StripeAmounts.toMinorUnits(amount, store.getCurrency().getCode());
			
			/*Map<String, Object> chargeParams = new HashMap<String, Object>();
			chargeParams.put("amount", minorAmount);
			chargeParams.put("capture", true);
			chargeParams.put("currency", store.getCurrency().getCode());
			chargeParams.put("source", token); // obtained with Stripe.js
			chargeParams.put("description", new StringBuilder().append(TRANSACTION).append(" - ").append(store.getStorename()).toString());
			*/

			RequestOptions requestOptions = requestOptions(apiKey);
			// The capture carries the attempt reference as idempotency key, so a retried
			// or replayed capture never takes the money twice.
			RequestOptions captureOptions = requestOptions(apiKey, attemptRef(payment));

			PaymentIntent paymentIntent = PaymentIntent.retrieve(token, requestOptions);

			checkCapturable(paymentIntent, minorAmount);

			PaymentIntentCaptureParams params =
					PaymentIntentCaptureParams.builder()
							.setAmountToCapture(minorAmount)
							.setStatementDescriptorSuffix(statementSuffix(store.getStorename()))
					.build();

			paymentIntent = paymentIntent.capture(params, captureOptions);
			
			
			transaction.setAmount(amount);
			//transaction.setOrder(order);
			transaction.setTransactionDate(new Date());
			transaction.setTransactionType(TransactionType.AUTHORIZECAPTURE);
			transaction.setPaymentType(PaymentType.CREDITCARD);
			transaction.getTransactionDetails().put("TRANSACTIONID", token);
			transaction.getTransactionDetails().put("TRNAPPROVED", paymentIntent.getStatus());
			transaction.getTransactionDetails().put("TRNORDERNUMBER", paymentIntent.getId());
			transaction.getTransactionDetails().put("MESSAGETEXT", null);
			
		} catch (Exception e) {

			e.printStackTrace();

			throw buildException(e);
	
		} 
		
		return transaction;  
		
	}

	@Override
	public Transaction refund(boolean partial, MerchantStore store, Transaction transaction,
			Order order, BigDecimal amount,
			IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {
		
		
		
		String apiKey = configuration.getIntegrationKeys().get("secretKey");

		if(StringUtils.isBlank(apiKey)) {
			IntegrationException te = new IntegrationException(
					"Can't process Stripe, missing payment.metaData");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
		}

		try {
		

			String trnID = transaction.getTransactionDetails().get("TRNORDERNUMBER");
			
			//stripe expects an integer amount in the currency minor unit
			//553.47 USD = 55347
			long minorAmount = StripeAmounts.toMinorUnits(amount, store.getCurrency().getCode());

			RequestOptions requestOptions = requestOptions(apiKey);

			Map<String, Object> params = new HashMap<>();
			params.put("payment_intent", trnID);
			params.put("amount", minorAmount);
			Refund re = createRefund(params, requestOptions);

			Transaction refund = new Transaction();
			refund.setAmount(amount);
			refund.setOrder(order);
			refund.setTransactionDate(new Date());
			refund.setTransactionType(TransactionType.REFUND);
			refund.setPaymentType(PaymentType.CREDITCARD);
			refund.getTransactionDetails().put("TRANSACTIONID", re.getId());
			refund.getTransactionDetails().put("TRNAPPROVED", re.getStatus());
			refund.getTransactionDetails().put("TRNORDERNUMBER", re.getId());
			refund.getTransactionDetails().put("REFUNDEDTRANSACTION", trnID);
			refund.getTransactionDetails().put("MESSAGETEXT", null);

			return refund;

			
		} catch(Exception e) {
			
			throw buildException(e);

		} 
		
		
		
	}
	
	/**
	 * Fails with a precise message when the PaymentIntent cannot be captured for the requested amount.
	 */
	private void checkCapturable(PaymentIntent paymentIntent, long minorAmount) throws IntegrationException {
		if(!"requires_capture".equals(paymentIntent.getStatus())
				|| paymentIntent.getAmountCapturable() == null
				|| minorAmount > paymentIntent.getAmountCapturable()) {
			IntegrationException te = new IntegrationException(
					"Can't capture Stripe PaymentIntent " + paymentIntent.getId() + ", status " + paymentIntent.getStatus()
					+ ", capturable " + paymentIntent.getAmountCapturable() + ", requested " + minorAmount);
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
		}
	}

	private IntegrationException buildException(Exception ex) {

	if(ex instanceof ApiConnectionException || ex instanceof ApiException) {
		// Network failure or Stripe server error: the request may have been applied.
		LOGGER.error("Stripe outcome unknown", ex);
		return new PaymentOutcomeUnknownException("Stripe did not answer clearly: " + ex.getMessage(), ex);
	}
		
	if(ex instanceof CardException) {
		  CardException e = (CardException)ex;
		  // Since it's a decline, CardException will be caught
		  //System.out.println("Status is: " + e.getCode());
		  //System.out.println("Message is: " + e.getMessage());
		  
		  
			/**
			 * 
				invalid_number 	The card number is not a valid credit card number.
				invalid_expiry_month 	The card's expiration month is invalid.
				invalid_expiry_year 	The card's expiration year is invalid.
				invalid_cvc 	The card's security code is invalid.
				incorrect_number 	The card number is incorrect.
				expired_card 	The card has expired.
				incorrect_cvc 	The card's security code is incorrect.
				incorrect_zip 	The card's zip code failed validation.
				card_declined 	The card was declined.
				missing 	There is no card on a customer that is being charged.
				processing_error 	An error occurred while processing the card.
				rate_limit 	An error occurred due to requests hitting the API too quickly. Please let us know if you're consistently running into this error.
			 */
		
			
			String declineCode = e.getDeclineCode();
			
			if("card_declined".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_PAYMENT_DECLINED);
				te.setMessageCode("message.payment.declined");
				te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
				return te;
			}
			
			if("invalid_number".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
				te.setMessageCode("messages.error.creditcard.number");
				te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
				return te;
			}
			
			if("invalid_expiry_month".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
				te.setMessageCode("messages.error.creditcard.dateformat");
				te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
				return te;
			}
			
			if("invalid_expiry_year".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
				te.setMessageCode("messages.error.creditcard.dateformat");
				te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
				return te;
			}
			
			if("invalid_cvc".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
				te.setMessageCode("messages.error.creditcard.cvc");
				te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
				return te;
			}
			
			if("incorrect_number".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
				te.setMessageCode("messages.error.creditcard.number");
				te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
				return te;
			}
			
			if("incorrect_cvc".equals(declineCode)) {
				IntegrationException te = new IntegrationException(
						"Can't process stripe message " + e.getMessage());
				te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
				te.setMessageCode("messages.error.creditcard.cvc");
				te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
				return te;
			}
			
			//nothing good so create generic error
			IntegrationException te = new IntegrationException(
					"Can't process stripe card  " + e.getMessage());
			te.setExceptionType(IntegrationException.EXCEPTION_VALIDATION);
			te.setMessageCode("messages.error.creditcard.number");
			te.setErrorCode(IntegrationException.EXCEPTION_VALIDATION);
			return te;
		

		  
	} else if (ex instanceof InvalidRequestException) {
		LOGGER.error("InvalidRequest error with stripe", ex.getMessage());
		InvalidRequestException e =(InvalidRequestException)ex;
		IntegrationException te = new IntegrationException(
				"Can't process Stripe, missing invalid payment parameters");
		te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
		te.setMessageCode("messages.error.creditcard.number");
		te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
		return te;
		
	} else if (ex instanceof AuthenticationException) {
		LOGGER.error("Authentication error with stripe", ex.getMessage());
		AuthenticationException e = (AuthenticationException)ex;
		  // Authentication with Stripe's API failed
		  // (maybe you changed API keys recently)
		IntegrationException te = new IntegrationException(
				"Can't process Stripe, missing invalid payment parameters");
		te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
		te.setMessageCode("message.payment.error");
		te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
		return te;
		
	} /*else if (ex instanceof APIConnectionException) {
		LOGGER.error("API connection error with stripe", ex.getMessage());
		APIConnectionException e = (APIConnectionException)ex;
		  // Network communication with Stripe failed
		IntegrationException te = new IntegrationException(
				"Can't process Stripe, missing invalid payment parameters");
		te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
		te.setMessageCode("message.payment.error");
		te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
		return te;
	} */else if (ex instanceof StripeException) {
		LOGGER.error("Error with stripe", ex.getMessage());
		StripeException e = (StripeException)ex;
		  // Display a very generic error to the user, and maybe send
		  // yourself an email
		IntegrationException te = new IntegrationException(
				"Can't process Stripe authorize, missing invalid payment parameters");
		te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
		te.setMessageCode("message.payment.error");
		te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
		return te;
		
		

	} else if (ex instanceof Exception) {
		LOGGER.error("Stripe module error", ex.getMessage());
		if(ex instanceof IntegrationException) {
			return (IntegrationException)ex;
		} else {
			IntegrationException te = new IntegrationException(
					"Can't process Stripe authorize, exception", ex);
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			return te;
		}


	} else {
		LOGGER.error("Stripe module error", ex.getMessage());
		IntegrationException te = new IntegrationException(
				"Can't process Stripe authorize, exception", ex);
		te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
		te.setMessageCode("message.payment.error");
		te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
		return te;
	}

	}
	
	




	/**
	 * Creates the refund on Stripe. Protected so tests can stub the network call.
	 */
	protected Refund createRefund(Map<String, Object> params, RequestOptions requestOptions) throws StripeException {
		return Refund.create(params, requestOptions);
	}

	/** Connect timeout of every Stripe call, in milliseconds. */
	@Value("${config.payment.stripe.connectTimeoutMs:3000}")
	private int connectTimeoutMs = StripeNetwork.DEFAULT_CONNECT_TIMEOUT_MS;

	/** Read timeout of every Stripe call, in milliseconds. */
	@Value("${config.payment.stripe.readTimeoutMs:15000}")
	private int readTimeoutMs = StripeNetwork.DEFAULT_READ_TIMEOUT_MS;

	/** Retries of a call that failed on the network; they reuse the call's idempotency key. */
	@Value("${config.payment.stripe.maxNetworkRetries:2}")
	private int maxNetworkRetries = StripeNetwork.DEFAULT_MAX_NETWORK_RETRIES;

	/**
	 * Per-call options carrying this store's secret key. The module is a singleton shared by
	 * every MerchantStore, so the key must never be written to the process-wide Stripe.apiKey.
	 * Timeouts are explicit: the SDK defaults (30 s connect, 80 s read) would hold a checkout
	 * thread for minutes. A timeout surfaces as ApiConnectionException, an unknown outcome.
	 */
	RequestOptions requestOptions(String apiKey) {
		return requestOptions(apiKey, null);
	}

	/** Same as {@link #requestOptions(String)} with an idempotency key when one is given. */
	RequestOptions requestOptions(String apiKey, String idempotencyKey) {
		return StripeNetwork.requestOptions(apiKey, idempotencyKey, connectTimeoutMs, readTimeoutMs,
				maxNetworkRetries);
	}

	private static String attemptRef(Payment payment) {
		if (payment.getPaymentMetaData() == null) {
			return null;
		}
		return StringUtils.trimToNull(payment.getPaymentMetaData().get(PaymentAttempt.META_ATTEMPT_REF));
	}

	/**
	 * Card payments reject a full statement_descriptor on current Stripe API
	 * versions; only a suffix appended to the account's prefix is allowed. Stripe
	 * caps the combined text at 22 characters, so the suffix stays short and
	 * drops characters Stripe refuses.
	 */
	static String statementSuffix(String storeName) {
		String cleaned = StringUtils.defaultString(storeName).replaceAll("[<>\\\\'\"*]", "").trim();
		if (cleaned.isEmpty()) {
			return null;
		}
		return cleaned.length() > 10 ? cleaned.substring(0, 10).trim() : cleaned;
	}

	/** Creates a PaymentIntent. Protected so tests can stub the network call. */
	protected PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions requestOptions)
			throws StripeException {
		return PaymentIntent.create(params, requestOptions);
	}

	/** Retrieves a PaymentIntent. Protected so tests can stub the network call. */
	protected PaymentIntent retrievePaymentIntent(String id, RequestOptions requestOptions) throws StripeException {
		return PaymentIntent.retrieve(id, requestOptions);
	}

	/** Cancels a PaymentIntent. Protected so tests can stub the network call. */
	protected PaymentIntent cancelPaymentIntent(PaymentIntent intent, RequestOptions requestOptions)
			throws StripeException {
		return intent.cancel(requestOptions);
	}

	/**
	 * Maps a PaymentIntent status to the gateway answer: succeeded is captured,
	 * requires_capture is authorised and held, processing is not final yet,
	 * canceled and requires_payment_method took no money. Anything else is
	 * PENDING so a person decides.
	 */
	static GatewayPaymentStatus toStatus(PaymentIntent intent, String currency) {
		String status = intent.getStatus();
		String id = intent.getId();
		if ("succeeded".equals(status)) {
			Long minor = intent.getAmountReceived() != null ? intent.getAmountReceived() : intent.getAmount();
			return GatewayPaymentStatus.succeeded(id, amount(minor, intent, currency), status);
		}
		if ("requires_capture".equals(status)) {
			Long minor = intent.getAmountCapturable() != null ? intent.getAmountCapturable() : intent.getAmount();
			return GatewayPaymentStatus.authorized(id, amount(minor, intent, currency), status);
		}
		if ("canceled".equals(status) || "requires_payment_method".equals(status)) {
			return GatewayPaymentStatus.failed(id, status);
		}
		return GatewayPaymentStatus.pending(id, status);
	}

	private static BigDecimal amount(Long minor, PaymentIntent intent, String currency) {
		String code = StringUtils.isNotBlank(intent.getCurrency()) ? intent.getCurrency() : currency;
		if (minor == null || StringUtils.isBlank(code)) {
			return null;
		}
		return StripeAmounts.fromMinorUnits(minor, code);
	}

	@Override
	public GatewayPaymentStatus lookup(MerchantStore store, IntegrationConfiguration configuration,
			PaymentAttempt attempt) throws IntegrationException {
		if (StringUtils.isBlank(attempt.getGatewayRef())) {
			// Without the PaymentIntent id there was nothing to capture.
			return GatewayPaymentStatus.notFound();
		}
		String apiKey = configuration.getIntegrationKeys().get("secretKey");
		try {
			PaymentIntent intent = retrievePaymentIntent(attempt.getGatewayRef(), requestOptions(apiKey));
			return toStatus(intent, attempt.getCurrency());
		} catch (InvalidRequestException e) {
			if ("resource_missing".equals(e.getCode())) {
				return GatewayPaymentStatus.notFound();
			}
			throw new IntegrationException("Stripe lookup of " + attempt.getGatewayRef() + " failed", e);
		} catch (StripeException e) {
			throw new IntegrationException("Stripe lookup of " + attempt.getGatewayRef() + " failed", e);
		}
	}

	@Override
	public Transaction compensate(MerchantStore store, IntegrationConfiguration configuration, PaymentAttempt attempt,
			GatewayPaymentStatus status) throws IntegrationException {
		String apiKey = configuration.getIntegrationKeys().get("secretKey");
		String intentId = StringUtils.isNotBlank(status.getGatewayTransactionId()) ? status.getGatewayTransactionId()
				: attempt.getGatewayRef();
		RequestOptions options = requestOptions(apiKey, "comp-" + attempt.getAttemptRef());
		try {
			Transaction refund = new Transaction();
			refund.setAmount(status.getAmount() != null ? status.getAmount() : attempt.getAmount());
			refund.setTransactionDate(new Date());
			refund.setTransactionType(TransactionType.REFUND);
			refund.setPaymentType(PaymentType.CREDITCARD);
			refund.getTransactionDetails().put("REFUNDEDTRANSACTION", intentId);
			refund.getTransactionDetails().put("MESSAGETEXT", null);
			if (!status.isCaptured()) {
				// authorised only: cancelling releases the hold, nothing to refund
				PaymentIntent intent = retrievePaymentIntent(intentId, requestOptions(apiKey));
				PaymentIntent canceled = cancelPaymentIntent(intent, options);
				refund.getTransactionDetails().put("TRANSACTIONID", canceled.getId());
				refund.getTransactionDetails().put("TRNAPPROVED", canceled.getStatus());
				refund.getTransactionDetails().put("TRNORDERNUMBER", canceled.getId());
			} else {
				Map<String, Object> params = new HashMap<>();
				params.put("payment_intent", intentId);
				Map<String, String> metadata = new HashMap<>();
				metadata.put("attempt_ref", attempt.getAttemptRef());
				params.put("metadata", metadata);
				Refund re = createRefund(params, options);
				refund.getTransactionDetails().put("TRANSACTIONID", re.getId());
				refund.getTransactionDetails().put("TRNAPPROVED", re.getStatus());
				refund.getTransactionDetails().put("TRNORDERNUMBER", re.getId());
			}
			return refund;
		} catch (StripeException e) {
			throw new IntegrationException("Stripe compensation of " + intentId + " failed", e);
		}
	}

}
