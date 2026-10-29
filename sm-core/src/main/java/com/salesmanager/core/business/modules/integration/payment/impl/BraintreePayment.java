package com.salesmanager.core.business.modules.integration.payment.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Environment;
import com.braintreegateway.Result;
import com.braintreegateway.ResourceCollection;
import com.braintreegateway.TransactionRequest;
import com.braintreegateway.TransactionSearchRequest;
import com.braintreegateway.ValidationError;
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
import com.salesmanager.core.modules.integration.payment.model.GatewayPaymentStatus;
import com.salesmanager.core.modules.integration.payment.model.PaymentCompensation;
import com.salesmanager.core.modules.integration.payment.model.PaymentModule;
import com.salesmanager.core.modules.integration.payment.model.PaymentStatusLookup;

public class BraintreePayment implements PaymentModule, PaymentStatusLookup, PaymentCompensation {

	private static final Logger LOGGER = LoggerFactory.getLogger(BraintreePayment.class);

	/** Braintree statuses meaning the sale was submitted for settlement (funds will be captured) */
	private static final EnumSet<com.braintreegateway.Transaction.Status> CAPTURED_STATUSES = EnumSet.of(
			com.braintreegateway.Transaction.Status.SUBMITTED_FOR_SETTLEMENT,
			com.braintreegateway.Transaction.Status.SETTLING,
			com.braintreegateway.Transaction.Status.SETTLED,
			com.braintreegateway.Transaction.Status.SETTLEMENT_PENDING);

	/** Not yet settled: can only be voided, for the full amount */
	private static final EnumSet<com.braintreegateway.Transaction.Status> VOIDABLE_STATUSES = EnumSet.of(
			com.braintreegateway.Transaction.Status.AUTHORIZED,
			com.braintreegateway.Transaction.Status.SUBMITTED_FOR_SETTLEMENT);

	/** Settled or settling: can be refunded, fully or partially */
	private static final EnumSet<com.braintreegateway.Transaction.Status> REFUNDABLE_STATUSES = EnumSet.of(
			com.braintreegateway.Transaction.Status.SETTLED,
			com.braintreegateway.Transaction.Status.SETTLING);

	private static IntegrationException refundException(String message) {
		IntegrationException te = new IntegrationException(message);
		te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
		te.setMessageCode("message.payment.error");
		te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
		return te;
	}

	/**
	 * Builds the Braintree gateway for the given configuration.
	 * Protected so it can be overridden in tests.
	 */
	protected BraintreeGateway gateway(IntegrationConfiguration configuration) {

		String merchantId = configuration.getIntegrationKeys().get("merchant_id");
		String publicKey = configuration.getIntegrationKeys().get("public_key");
		String privateKey = configuration.getIntegrationKeys().get("private_key");

		Environment environment= Environment.PRODUCTION;
		if (configuration.getEnvironment().equals("TEST")) {// sandbox
			environment= Environment.SANDBOX;
		}

		return new BraintreeGateway(
				environment,
				merchantId,
				publicKey,
				privateKey
				);
	}

	@Override
	public void validateModuleConfiguration(IntegrationConfiguration integrationConfiguration, MerchantStore store)
			throws IntegrationException {
		List<String> errorFields = null;
		
		
		Map<String,String> keys = integrationConfiguration.getIntegrationKeys();
		
		//validate integrationKeys['merchant_id']
		if(keys==null || StringUtils.isBlank(keys.get("merchant_id"))) {
			errorFields = new ArrayList<String>();
			errorFields.add("merchant_id");
		}
		
		//validate integrationKeys['public_key']
		if(keys==null || StringUtils.isBlank(keys.get("public_key"))) {
			if(errorFields==null) {
				errorFields = new ArrayList<String>();
			}
			errorFields.add("public_key");
		}
		
		//validate integrationKeys['private_key']
		if(keys==null || StringUtils.isBlank(keys.get("private_key"))) {
			if(errorFields==null) {
				errorFields = new ArrayList<String>();
			}
			errorFields.add("private_key");
		}
		
		//validate integrationKeys['tokenization_key']
		if(keys==null || StringUtils.isBlank(keys.get("tokenization_key"))) {
			if(errorFields==null) {
				errorFields = new ArrayList<String>();
			}
			errorFields.add("tokenization_key");
		}
		
		
		if(errorFields!=null) {
			IntegrationException ex = new IntegrationException(IntegrationException.ERROR_VALIDATION_SAVE);
			ex.setErrorFields(errorFields);
			throw ex;
			
		}

	}

	@Override
	public Transaction initTransaction(MerchantStore store, Customer customer, BigDecimal amount, Payment payment,
			IntegrationConfiguration configuration, IntegrationModule module) throws IntegrationException {

		Validate.notNull(configuration,"Configuration cannot be null");
		
		String merchantId = configuration.getIntegrationKeys().get("merchant_id");
		String publicKey = configuration.getIntegrationKeys().get("public_key");
		String privateKey = configuration.getIntegrationKeys().get("private_key");
		
		Validate.notNull(merchantId,"merchant_id cannot be null");
		Validate.notNull(publicKey,"public_key cannot be null");
		Validate.notNull(privateKey,"private_key cannot be null");
		
	    BraintreeGateway gateway = gateway(configuration);
		
		String clientToken = gateway.clientToken().generate();

		Transaction transaction = new Transaction();
		transaction.setAmount(amount);
		transaction.setDetails(clientToken);
		transaction.setPaymentType(payment.getPaymentType());
		transaction.setTransactionDate(new Date());
		transaction.setTransactionType(payment.getTransactionType());
		
		return transaction;
	}

	@Override
	public Transaction authorize(MerchantStore store, Customer customer, List<ShoppingCartItem> items,
			BigDecimal amount, Payment payment, IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {


		Validate.notNull(configuration,"Configuration cannot be null");
		
		String merchantId = configuration.getIntegrationKeys().get("merchant_id");
		String publicKey = configuration.getIntegrationKeys().get("public_key");
		String privateKey = configuration.getIntegrationKeys().get("private_key");
		
		Validate.notNull(merchantId,"merchant_id cannot be null");
		Validate.notNull(publicKey,"public_key cannot be null");
		Validate.notNull(privateKey,"private_key cannot be null");
		
		String nonce = payment.getPaymentMetaData().get("paymentToken");
		
	    if(StringUtils.isBlank(nonce)) {
			IntegrationException te = new IntegrationException(
						"Can't process Braintree, missing authorization nounce");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
	    }
		
	    BraintreeGateway gateway = gateway(configuration);
	    
	   

        TransactionRequest request = new TransactionRequest()
            .amount(amount)
            .paymentMethodNonce(nonce);
        String attemptRef = attemptRef(payment);
        if (attemptRef != null) {
        	// lets the reconciler find this sale by searching on order id
        	request.orderId(attemptRef);
        }

        Result<com.braintreegateway.Transaction> result = gateway.transaction().sale(request);

        String authorizationId = null;
        
        if (result.isSuccess()) {
        	com.braintreegateway.Transaction transaction = result.getTarget();
        	authorizationId  = transaction.getId();
        } else if (result.getTransaction() != null) {
        	com.braintreegateway.Transaction transaction = result.getTransaction();
        	authorizationId = transaction.getAuthorizedTransactionId();
        } else {
            String errorString = "";
            for (ValidationError error : result.getErrors().getAllDeepValidationErrors()) {
               errorString += "Error: " + error.getCode() + ": " + error.getMessage() + "\n";
            }
            
			IntegrationException te = new IntegrationException(
					"Can't process Braintree authorization " + errorString);
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;

        }
        
        if(StringUtils.isBlank(authorizationId)) {
			IntegrationException te = new IntegrationException(
					"Can't process Braintree, missing authorizationId");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
        }
        
        Transaction trx = new Transaction();
        trx.setAmount(amount);
        trx.setTransactionDate(new Date());
        trx.setTransactionType(TransactionType.AUTHORIZE);
        trx.setPaymentType(PaymentType.CREDITCARD);
        trx.getTransactionDetails().put("TRANSACTIONID", authorizationId);
        trx.getTransactionDetails().put("TRNAPPROVED", null);
        trx.getTransactionDetails().put("TRNORDERNUMBER", authorizationId);
        trx.getTransactionDetails().put("MESSAGETEXT", null);
        
        return trx;
		
	}

	@Override
	public Transaction capture(MerchantStore store, Customer customer, Order order, Transaction capturableTransaction,
			IntegrationConfiguration configuration, IntegrationModule module) throws IntegrationException {
		Validate.notNull(configuration,"Configuration cannot be null");
		
		String merchantId = configuration.getIntegrationKeys().get("merchant_id");
		String publicKey = configuration.getIntegrationKeys().get("public_key");
		String privateKey = configuration.getIntegrationKeys().get("private_key");
		
		Validate.notNull(merchantId,"merchant_id cannot be null");
		Validate.notNull(publicKey,"public_key cannot be null");
		Validate.notNull(privateKey,"private_key cannot be null");
		
		String auth = capturableTransaction.getTransactionDetails().get("TRANSACTIONID");
		
	    if(StringUtils.isBlank(auth)) {
			IntegrationException te = new IntegrationException(
						"Can't process Braintree, missing authorization id");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
	    }
		
	    BraintreeGateway gateway = gateway(configuration);
	    
	   
	    BigDecimal amount = order.getTotal();

        Result<com.braintreegateway.Transaction> result = gateway.transaction().submitForSettlement(auth, amount);

        String trxId = null;
        
        if (result.isSuccess()) {
        	com.braintreegateway.Transaction settledTransaction = result.getTarget();
        	trxId = settledTransaction.getId();
        } else {
            String errorString = "";
            for (ValidationError error : result.getErrors().getAllDeepValidationErrors()) {
               errorString += "Error: " + error.getCode() + ": " + error.getMessage() + "\n";
            }
            
			IntegrationException te = new IntegrationException(
					"Can't process Braintree refund " + errorString);
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;

        }
        
        if(StringUtils.isBlank(trxId)) {
			IntegrationException te = new IntegrationException(
					"Can't process Braintree, missing original transaction");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
        }
        
        Transaction trx = new Transaction();
        trx.setAmount(amount);
        trx.setTransactionDate(new Date());
        trx.setTransactionType(TransactionType.CAPTURE);
        trx.setPaymentType(PaymentType.CREDITCARD);
        trx.getTransactionDetails().put("TRANSACTIONID", trxId);
        trx.getTransactionDetails().put("TRNAPPROVED", null);
        trx.getTransactionDetails().put("TRNORDERNUMBER", trxId);
        trx.getTransactionDetails().put("MESSAGETEXT", null);
        
        return trx;
		
		
	}

	@Override
	public Transaction authorizeAndCapture(MerchantStore store, Customer customer, List<ShoppingCartItem> items,
			BigDecimal amount, Payment payment, IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {

		Validate.notNull(configuration,"Configuration cannot be null");
		
		String merchantId = configuration.getIntegrationKeys().get("merchant_id");
		String publicKey = configuration.getIntegrationKeys().get("public_key");
		String privateKey = configuration.getIntegrationKeys().get("private_key");
		
		Validate.notNull(merchantId,"merchant_id cannot be null");
		Validate.notNull(publicKey,"public_key cannot be null");
		Validate.notNull(privateKey,"private_key cannot be null");
		
		String nonce = payment.getPaymentMetaData().get("paymentToken");//paymentToken
		
	    if(StringUtils.isBlank(nonce)) {
			IntegrationException te = new IntegrationException(
						"Can't process Braintree, missing authorization nounce");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
	    }
		
	    BraintreeGateway gateway = gateway(configuration);
	    
	   

        TransactionRequest request = new TransactionRequest()
            .amount(amount)
            .paymentMethodNonce(nonce)
            .options()
                .submitForSettlement(true)
                .done();
        String attemptRef = attemptRef(payment);
        if (attemptRef != null) {
        	// lets the reconciler find this sale by searching on order id
        	request.orderId(attemptRef);
        }

        Result<com.braintreegateway.Transaction> result = gateway.transaction().sale(request);

        String trxId = null;
        String status = null;
        TransactionType transactionType = TransactionType.AUTHORIZECAPTURE;
        
        if (result.isSuccess()) {
        	com.braintreegateway.Transaction transaction = result.getTarget();
        	trxId  = transaction.getId();
        	com.braintreegateway.Transaction.Status trxStatus = transaction.getStatus();
        	status = trxStatus != null ? trxStatus.name() : null;
        	if (trxStatus == null || !CAPTURED_STATUSES.contains(trxStatus)) {
        		//funds are held but not submitted for settlement, keep it capturable
        		LOGGER.warn("Braintree sale " + trxId + " returned status " + status
        				+ ", recording as AUTHORIZE so it can be captured");
        		transactionType = TransactionType.AUTHORIZE;
        	}
        } else {
            String errorString = "";
            for (ValidationError error : result.getErrors().getAllDeepValidationErrors()) {
               errorString += "Error: " + error.getCode() + ": " + error.getMessage() + "\n";
            }
            
			IntegrationException te = new IntegrationException(
					"Can't process Braintree auth + capture " + errorString);
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;

        }
        
        if(StringUtils.isBlank(trxId)) {
			IntegrationException te = new IntegrationException(
					"Can't process Braintree, missing trxId");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
        }
        
        Transaction trx = new Transaction();
        trx.setAmount(amount);
        trx.setTransactionDate(new Date());
        trx.setTransactionType(transactionType);
        trx.setPaymentType(PaymentType.CREDITCARD);
        trx.getTransactionDetails().put("TRANSACTIONID", trxId);
        trx.getTransactionDetails().put("TRNAPPROVED", status);
        trx.getTransactionDetails().put("TRNORDERNUMBER", trxId);
        trx.getTransactionDetails().put("MESSAGETEXT", null);
        
        return trx;
		
		
	}

	@Override
	public Transaction refund(boolean partial, MerchantStore store, Transaction transaction, Order order,
			BigDecimal amount, IntegrationConfiguration configuration, IntegrationModule module)
			throws IntegrationException {

		
		String merchantId = configuration.getIntegrationKeys().get("merchant_id");
		String publicKey = configuration.getIntegrationKeys().get("public_key");
		String privateKey = configuration.getIntegrationKeys().get("private_key");
		
		Validate.notNull(merchantId,"merchant_id cannot be null");
		Validate.notNull(publicKey,"public_key cannot be null");
		Validate.notNull(privateKey,"private_key cannot be null");
		
		String auth = transaction.getTransactionDetails().get("TRANSACTIONID");
		
	    if(StringUtils.isBlank(auth)) {
			IntegrationException te = new IntegrationException(
						"Can't process Braintree refund, missing transaction id");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
	    }
		
	    BraintreeGateway gateway = gateway(configuration);

	    // Braintree only refunds settled (or settling) transactions; anything
	    // not yet settled has to be voided instead, which releases the full amount.
	    com.braintreegateway.Transaction original;
	    try {
	    	original = gateway.transaction().find(auth);
	    } catch (com.braintreegateway.exceptions.NotFoundException nfe) {
	    	throw refundException("Can't process Braintree refund, transaction " + auth + " not found");
	    }
	    com.braintreegateway.Transaction.Status status = original == null ? null : original.getStatus();

        Result<com.braintreegateway.Transaction> result;
        if (VOIDABLE_STATUSES.contains(status)) {
        	if (partial) {
        		throw refundException("Can't partially refund Braintree transaction " + auth + " in status " + status
        				+ "; it is not settled yet and can only be voided for the full amount");
        	}
        	result = gateway.transaction().voidTransaction(auth);
        } else if (REFUNDABLE_STATUSES.contains(status)) {
        	result = gateway.transaction().refund(auth, amount);
        } else {
        	throw refundException("Can't refund Braintree transaction " + auth + " in status " + status);
        }

        String trxId = null;
        
        if (result.isSuccess()) {
        	com.braintreegateway.Transaction settledTransaction = result.getTarget();
        	trxId = settledTransaction.getId();
        } else {
            String errorString = "";
            for (ValidationError error : result.getErrors().getAllDeepValidationErrors()) {
               errorString += "Error: " + error.getCode() + ": " + error.getMessage() + "\n";
            }
            
			IntegrationException te = new IntegrationException(
					"Can't process Braintree refund " + errorString);
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;

        }
        
        if(StringUtils.isBlank(trxId)) {
			IntegrationException te = new IntegrationException(
					"Can't process Braintree refund, missing original transaction");
			te.setExceptionType(IntegrationException.TRANSACTION_EXCEPTION);
			te.setMessageCode("message.payment.error");
			te.setErrorCode(IntegrationException.TRANSACTION_EXCEPTION);
			throw te;
        }
        
        Transaction trx = new Transaction();
        trx.setAmount(amount);
        trx.setTransactionDate(new Date());
        trx.setTransactionType(TransactionType.REFUND);
        trx.setPaymentType(PaymentType.CREDITCARD);
        trx.getTransactionDetails().put("TRANSACTIONID", trxId);
        trx.getTransactionDetails().put("TRNAPPROVED", VOIDABLE_STATUSES.contains(status) ? "VOIDED" : "REFUNDED");
        trx.getTransactionDetails().put("TRNORDERNUMBER", trxId);
        trx.getTransactionDetails().put("MESSAGETEXT", null);
        
        return trx;
		
	}

	private static String attemptRef(Payment payment) {
		if (payment.getPaymentMetaData() == null) {
			return null;
		}
		return StringUtils.trimToNull(payment.getPaymentMetaData().get(PaymentAttempt.META_ATTEMPT_REF));
	}

	/** Braintree statuses that mean money was taken or is held. */
	private static final EnumSet<com.braintreegateway.Transaction.Status> SUCCEEDED_STATUSES = EnumSet.of(
			com.braintreegateway.Transaction.Status.SUBMITTED_FOR_SETTLEMENT,
			com.braintreegateway.Transaction.Status.SETTLING,
			com.braintreegateway.Transaction.Status.SETTLED,
			com.braintreegateway.Transaction.Status.SETTLEMENT_PENDING,
			com.braintreegateway.Transaction.Status.SETTLEMENT_CONFIRMED,
			com.braintreegateway.Transaction.Status.AUTHORIZED);

	/** Braintree statuses that mean no money was, or will be, taken. */
	private static final EnumSet<com.braintreegateway.Transaction.Status> FAILED_STATUSES = EnumSet.of(
			com.braintreegateway.Transaction.Status.FAILED,
			com.braintreegateway.Transaction.Status.GATEWAY_REJECTED,
			com.braintreegateway.Transaction.Status.PROCESSOR_DECLINED,
			com.braintreegateway.Transaction.Status.VOIDED,
			com.braintreegateway.Transaction.Status.AUTHORIZATION_EXPIRED,
			com.braintreegateway.Transaction.Status.SETTLEMENT_DECLINED);

	/**
	 * Maps one Braintree transaction to the gateway answer. Anything else
	 * (AUTHORIZING, UNRECOGNIZED, null) is PENDING so a person decides.
	 */
	static GatewayPaymentStatus toStatus(com.braintreegateway.Transaction.Status status, String id, BigDecimal amount) {
		String name = status == null ? null : status.name();
		if (status != null && SUCCEEDED_STATUSES.contains(status)) {
			return status == com.braintreegateway.Transaction.Status.AUTHORIZED
					? GatewayPaymentStatus.authorized(id, amount, name)
					: GatewayPaymentStatus.succeeded(id, amount, name);
		}
		if (status != null && FAILED_STATUSES.contains(status)) {
			return GatewayPaymentStatus.failed(id, name);
		}
		return GatewayPaymentStatus.pending(id, name);
	}

	@Override
	public GatewayPaymentStatus lookup(MerchantStore store, IntegrationConfiguration configuration,
			PaymentAttempt attempt) throws IntegrationException {
		BraintreeGateway gateway = gateway(configuration);
		ResourceCollection<com.braintreegateway.Transaction> found = gateway.transaction()
				.search(new TransactionSearchRequest().orderId().is(attempt.getAttemptRef()));
		GatewayPaymentStatus best = null;
		for (com.braintreegateway.Transaction t : found) {
			GatewayPaymentStatus s = toStatus(t.getStatus(), t.getId(), t.getAmount());
			// one success wins; a PENDING sale beats a failed retry
			if (best == null || rank(s) > rank(best)) {
				best = s;
			}
		}
		return best == null ? GatewayPaymentStatus.notFound() : best;
	}

	private static int rank(GatewayPaymentStatus s) {
		switch (s.getOutcome()) {
		case SUCCEEDED:
			return 3;
		case PENDING:
			return 2;
		case FAILED:
			return 1;
		default:
			return 0;
		}
	}

	@Override
	public Transaction compensate(MerchantStore store, IntegrationConfiguration configuration, PaymentAttempt attempt,
			GatewayPaymentStatus status) throws IntegrationException {
		String id = status.getGatewayTransactionId();
		if (StringUtils.isBlank(id)) {
			throw refundException("Can't compensate Braintree attempt " + attempt.getAttemptRef()
					+ ", no gateway transaction id");
		}
		BraintreeGateway gateway = gateway(configuration);
		com.braintreegateway.Transaction original = gateway.transaction().find(id);
		com.braintreegateway.Transaction.Status current = original == null ? null : original.getStatus();
		Result<com.braintreegateway.Transaction> result;
		if (current != null && VOIDABLE_STATUSES.contains(current)) {
			result = gateway.transaction().voidTransaction(id);
		} else if (current != null && REFUNDABLE_STATUSES.contains(current)) {
			result = gateway.transaction().refund(id);
		} else {
			throw refundException("Can't compensate Braintree transaction " + id + " in status " + current);
		}
		if (!result.isSuccess()) {
			throw refundException("Braintree compensation of " + id + " failed: " + result.getMessage());
		}
		BigDecimal amount = status.getAmount() != null ? status.getAmount() : attempt.getAmount();
		Transaction trx = new Transaction();
		trx.setAmount(amount);
		trx.setTransactionDate(new Date());
		trx.setTransactionType(TransactionType.REFUND);
		trx.setPaymentType(PaymentType.CREDITCARD);
		String refundId = result.getTarget() == null ? id : result.getTarget().getId();
		trx.getTransactionDetails().put("TRANSACTIONID", refundId);
		trx.getTransactionDetails().put("TRNAPPROVED", VOIDABLE_STATUSES.contains(current) ? "VOIDED" : "REFUNDED");
		trx.getTransactionDetails().put("TRNORDERNUMBER", refundId);
		trx.getTransactionDetails().put("REFUNDEDTRANSACTION", id);
		trx.getTransactionDetails().put("MESSAGETEXT", null);
		return trx;
	}

}
