package com.salesmanager.core.business.services.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.repositories.payments.TransactionRepository;
import com.salesmanager.core.business.services.common.generic.SalesManagerEntityServiceImpl;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;



@Service("transactionService")
public class TransactionServiceImpl  extends SalesManagerEntityServiceImpl<Long, Transaction> implements TransactionService {
	

	private TransactionRepository transactionRepository;
	
	@Inject
	public TransactionServiceImpl(TransactionRepository transactionRepository) {
		super(transactionRepository);
		this.transactionRepository = transactionRepository;
	}
	
	@Override
	public void create(Transaction transaction) throws ServiceException {
		
		//parse JSON string
		String transactionDetails = transaction.toJSONString();
		if(!StringUtils.isBlank(transactionDetails)) {
			transaction.setDetails(transactionDetails);
		}
		
		super.create(transaction);
		
		
	}
	
	@Override
	public List<Transaction> listTransactions(Order order) throws ServiceException {
		
		List<Transaction> transactions = chronological(order);
		for(Transaction transaction : transactions) {
			parseDetails(transaction);
		}
		return transactions;
	}
	
	/**
	 * Orders transactions chronologically: by audit date created, then by
	 * transaction date when the audit date is missing, then by id. Transaction
	 * type plays no role in ordering.
	 */
	static final Comparator<Transaction> CHRONOLOGICAL = Comparator
			.comparing(TransactionServiceImpl::createdDate, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

	private static Date createdDate(Transaction transaction) {
		if (transaction.getAuditSection() != null && transaction.getAuditSection().getDateCreated() != null) {
			return transaction.getAuditSection().getDateCreated();
		}
		return transaction.getTransactionDate();
	}

	/** Transactions of an order, oldest first. */
	private List<Transaction> chronological(Order order) {
		List<Transaction> transactions = transactionRepository.findByOrder(order.getId());
		if (transactions == null) {
			return new ArrayList<Transaction>();
		}
		List<Transaction> sorted = new ArrayList<Transaction>(transactions);
		sorted.sort(CHRONOLOGICAL);
		return sorted;
	}

	private void parseDetails(Transaction transaction) throws ServiceException {
		if (!StringUtils.isBlank(transaction.getDetails())) {
			try {
				@SuppressWarnings("unchecked")
				Map<String, String> objects = new ObjectMapper().readValue(transaction.getDetails(), Map.class);
				transaction.setTransactionDetails(objects);
			} catch (Exception e) {
				throw new ServiceException(e);
			}
		}
	}

	/**
	 * Most recent transaction of the order (by creation date, then id), or null
	 * when the order has no transaction.
	 */
	@Override
	public Transaction lastTransaction(Order order, MerchantStore store) throws ServiceException {
		List<Transaction> transactions = chronological(order);
		if (transactions.isEmpty()) {
			return null;
		}
		return transactions.get(transactions.size() - 1);
	}

	/**
	 * The latest AUTHORIZE transaction, provided no CAPTURE, AUTHORIZECAPTURE or
	 * REFUND was recorded after it. Returns null when nothing is capturable.
	 */
	@Override
	public Transaction getCapturableTransaction(Order order)
			throws ServiceException {
		Transaction capturable = null;
		for (Transaction transaction : chronological(order)) {
			TransactionType type = transaction.getTransactionType();
			if (type == TransactionType.AUTHORIZE) {
				capturable = transaction;
			} else if (type == TransactionType.CAPTURE || type == TransactionType.AUTHORIZECAPTURE
					|| type == TransactionType.REFUND) {
				capturable = null;
			}
		}
		if (capturable != null) {
			parseDetails(capturable);
		}
		return capturable;
	}

	/**
	 * The latest CAPTURE or AUTHORIZECAPTURE transaction, i.e. the gateway
	 * transaction a refund applies to. Returns null when nothing was captured.
	 */
	@Override
	public Transaction getRefundableTransaction(Order order)
		throws ServiceException {
		Transaction refundable = null;
		for (Transaction transaction : chronological(order)) {
			TransactionType type = transaction.getTransactionType();
			if (type == TransactionType.CAPTURE || type == TransactionType.AUTHORIZECAPTURE) {
				refundable = transaction;
			}
		}
		if (refundable != null) {
			parseDetails(refundable);
		}
		return refundable;
	}

	@Override
	public BigDecimal getCapturedAmount(Order order) throws ServiceException {
		return sum(order, EnumSet.of(TransactionType.CAPTURE, TransactionType.AUTHORIZECAPTURE));
	}

	@Override
	public BigDecimal getRefundedAmount(Order order) throws ServiceException {
		return sum(order, EnumSet.of(TransactionType.REFUND));
	}

	@Override
	public BigDecimal getRefundableAmount(Order order) throws ServiceException {
		BigDecimal remaining = getCapturedAmount(order).subtract(getRefundedAmount(order));
		return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
	}

	private BigDecimal sum(Order order, Set<TransactionType> types) {
		BigDecimal total = BigDecimal.ZERO;
		for (Transaction transaction : chronological(order)) {
			if (types.contains(transaction.getTransactionType()) && transaction.getAmount() != null) {
				total = total.add(transaction.getAmount());
			}
		}
		return total;
	}

	@Override
	public List<Transaction> listTransactions(Date startDate, Date endDate) throws ServiceException {
		
		return transactionRepository.findByDates(startDate, endDate);
	}

}
