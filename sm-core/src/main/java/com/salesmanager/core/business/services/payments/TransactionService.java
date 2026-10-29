package com.salesmanager.core.business.services.payments;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.common.generic.SalesManagerEntityService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Transaction;




public interface TransactionService extends SalesManagerEntityService<Long, Transaction> {

	/**
	 * Obtain a previous transaction that has type authorize for a give order
	 * @param order
	 * @return
	 * @throws ServiceException
	 */
	Transaction getCapturableTransaction(Order order) throws ServiceException;

	Transaction getRefundableTransaction(Order order) throws ServiceException;

	List<Transaction> listTransactions(Order order) throws ServiceException;
	
	List<Transaction> listTransactions(Date startDate, Date endDate) throws ServiceException;
	
	/** Most recent transaction by creation date then id, null when none. */
	Transaction lastTransaction(Order order, MerchantStore store) throws ServiceException;

	/** Sum of CAPTURE and AUTHORIZECAPTURE amounts for the order. */
	BigDecimal getCapturedAmount(Order order) throws ServiceException;

	/** Sum of REFUND amounts for the order. */
	BigDecimal getRefundedAmount(Order order) throws ServiceException;

	/** Captured minus refunded, never negative. */
	BigDecimal getRefundableAmount(Order order) throws ServiceException;



}