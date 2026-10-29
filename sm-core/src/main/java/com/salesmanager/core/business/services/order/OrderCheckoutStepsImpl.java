package com.salesmanager.core.business.services.order;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderproduct.OrderProduct;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.PaymentType;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;

/**
 * Deliberately a separate bean that does not implement
 * TransactionalAspectAwareService: the XML advisor does not apply, and the
 * explicit REQUIRES_NEW boundaries are honoured because OrderServiceImpl calls
 * these methods through the Spring proxy.
 */
@Service("orderCheckoutSteps")
public class OrderCheckoutStepsImpl implements OrderCheckoutSteps {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderCheckoutStepsImpl.class);

	@PersistenceContext
	private EntityManager em;

	@Inject
	private CustomerService customerService;

	@Inject
	private TransactionService transactionService;

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Order createPendingOrder(Order order, Customer customer, List<ShoppingCartItem> items,
			Transaction transaction) throws ServiceException {

		for (ShoppingCartItem item : items) {
			reserve(item);
		}

		if (customer.getId() == null || customer.getId() == 0) {
			customerService.create(customer);
		}
		order.setCustomerId(customer.getId());

		order.setStatus(OrderStatus.PAYMENT_PENDING);
		if (order.getOrderHistory() == null) {
			order.setOrderHistory(new HashSet<>());
		}
		order.getOrderHistory().add(history(order, OrderStatus.PAYMENT_PENDING, null));

		em.persist(order);

		if (transaction != null) {
			transaction.setOrder(order);
			if (transaction.getId() == null || transaction.getId() == 0) {
				transactionService.create(transaction);
			} else {
				transactionService.update(transaction);
			}
		}
		em.flush();
		return order;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public void markPaymentSucceeded(Long orderId, OrderStatus finalStatus, Transaction paymentTransaction)
			throws ServiceException {
		// Compare-and-set in the database: only a PAYMENT_PENDING order can be finalised,
		// and only once. In-memory copies of the order from earlier in the request may
		// already carry the target status, so they are not trusted here.
		OrderStatusTransitions.check(OrderStatus.PAYMENT_PENDING, finalStatus);
		int updated = em.createQuery("update Order o set o.status = :to where o.id = :id and o.status = :from")
				.setParameter("to", finalStatus)
				.setParameter("id", orderId)
				.setParameter("from", OrderStatus.PAYMENT_PENDING)
				.executeUpdate();
		Order order = em.find(Order.class, orderId);
		if (order == null) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR, "Order " + orderId + " not found");
		}
		em.refresh(order);
		if (updated == 0 && order.getStatus() != finalStatus) {
			throw new IllegalOrderStatusTransitionException(order.getStatus(), finalStatus);
		}
		order.getOrderHistory().add(history(order, finalStatus, null));

		if (paymentTransaction != null) {
			paymentTransaction.setOrder(order);
			if (paymentTransaction.getId() == null || paymentTransaction.getId() == 0) {
				transactionService.create(paymentTransaction);
			} else {
				transactionService.update(paymentTransaction);
			}
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPaymentFailed(Long orderId, List<ShoppingCartItem> items, String reason) {
		Order order = em.find(Order.class, orderId);
		if (order == null) {
			LOGGER.error("Cannot mark order {} as payment failed: not found", orderId);
			return;
		}
		if (!OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.PAYMENT_FAILED)) {
			LOGGER.error("Order {} in status {} cannot move to PAYMENT_FAILED", orderId, order.getStatus());
			return;
		}
		order.setStatus(OrderStatus.PAYMENT_FAILED);
		recordFailureAndRelease(order, items, reason);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public boolean reconcilePaidOrder(Long orderId, String comment) throws ServiceException {
		Order order = em.find(Order.class, orderId);
		if (order == null) {
			throw new ServiceException(ServiceException.EXCEPTION_ERROR, "Order " + orderId + " not found");
		}
		OrderStatus target = order.getPaymentType() == PaymentType.MONEYORDER ? OrderStatus.ORDERED
				: OrderStatus.PROCESSED;
		if (!compareAndSet(orderId, OrderStatus.PAYMENT_PENDING, target)) {
			return false;
		}
		em.refresh(order);
		order.getOrderHistory().add(history(order, target, StringUtils.abbreviate(comment, 250)));
		return true;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean reconcileUnpaidOrder(Long orderId, String reason) {
		// The conditional update is the claim: only the instance whose update
		// matched the PAYMENT_PENDING row goes on to return the stock.
		if (!compareAndSet(orderId, OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_FAILED)) {
			return false;
		}
		Order order = em.find(Order.class, orderId);
		em.refresh(order);
		recordFailureAndRelease(order, itemsOf(order), reason);
		return true;
	}

	private boolean compareAndSet(Long orderId, OrderStatus from, OrderStatus to) {
		OrderStatusTransitions.check(from, to);
		return em.createQuery("update Order o set o.status = :to where o.id = :id and o.status = :from")
				.setParameter("to", to)
				.setParameter("id", orderId)
				.setParameter("from", from)
				.executeUpdate() == 1;
	}

	private void recordFailureAndRelease(Order order, List<ShoppingCartItem> items, String reason) {
		order.getOrderHistory().add(history(order, OrderStatus.PAYMENT_FAILED, StringUtils.abbreviate(reason, 250)));
		for (ShoppingCartItem item : items) {
			release(item);
		}
	}

	/**
	 * Rebuilds the reserved lines from the stored order products. Order lines
	 * keep only the SKU, so it is resolved to a variant first and then to a
	 * product of the order's store; lines that no longer resolve are skipped.
	 */
	private List<ShoppingCartItem> itemsOf(Order order) {
		List<ShoppingCartItem> items = new ArrayList<>();
		if (order.getOrderProducts() == null) {
			return items;
		}
		Integer storeId = order.getMerchant() == null ? null : order.getMerchant().getId();
		for (OrderProduct line : order.getOrderProducts()) {
			if (StringUtils.isBlank(line.getSku())) {
				continue;
			}
			ShoppingCartItem item = new ShoppingCartItem();
			item.setQuantity(line.getProductQuantity());
			List<Object[]> variants = em.createQuery("select v.product.id, v.id from ProductVariant v"
					+ " where v.sku = :s and v.product.merchantStore.id = :m", Object[].class)
					.setParameter("s", line.getSku()).setParameter("m", storeId).setMaxResults(1).getResultList();
			if (!variants.isEmpty()) {
				item.setProductId((Long) variants.get(0)[0]);
				item.setVariant((Long) variants.get(0)[1]);
			} else {
				List<Long> products = em.createQuery(
						"select p.id from Product p where p.sku = :s and p.merchantStore.id = :m", Long.class)
						.setParameter("s", line.getSku()).setParameter("m", storeId).setMaxResults(1).getResultList();
				if (products.isEmpty()) {
					LOGGER.warn("Order {} line with sku {} no longer matches a product; stock not returned",
							order.getId(), line.getSku());
					continue;
				}
				item.setProductId(products.get(0));
			}
			items.add(item);
		}
		return items;
	}

	/**
	 * Conditional decrement: a row is only updated when it still holds enough
	 * stock. The UPDATE takes a row lock, so two concurrent checkouts for the
	 * last unit serialise and the second one updates zero rows and fails.
	 */
	private void reserve(ShoppingCartItem item) throws ServiceException {
		int qty = item.getQuantity() == null ? 1 : item.getQuantity();
		long rows = countAvailabilities(item);
		if (rows == 0) {
			return; // product does not track inventory
		}
		Query update = em.createQuery("update ProductAvailability a set a.productQuantity = a.productQuantity - :q"
				+ " where a.product.id = :p and " + variantClause(item) + " and a.productQuantity >= :q");
		bind(update, item).setParameter("q", qty);
		int updated = update.executeUpdate();
		if (updated != rows) {
			LOGGER.warn("Insufficient inventory for product {} (requested {})", item.getProductId(), qty);
			throw new ServiceException(ServiceException.EXCEPTION_INVENTORY_MISMATCH,
					"Insufficient inventory for product " + item.getProductId());
		}
	}

	private void release(ShoppingCartItem item) {
		int qty = item.getQuantity() == null ? 1 : item.getQuantity();
		Query update = em.createQuery("update ProductAvailability a set a.productQuantity = a.productQuantity + :q"
				+ " where a.product.id = :p and " + variantClause(item));
		bind(update, item).setParameter("q", qty);
		update.executeUpdate();
	}

	private long countAvailabilities(ShoppingCartItem item) {
		Query count = em.createQuery(
				"select count(a) from ProductAvailability a where a.product.id = :p and " + variantClause(item));
		return ((Number) bind(count, item).getSingleResult()).longValue();
	}

	private static String variantClause(ShoppingCartItem item) {
		return item.getVariant() != null ? "a.productVariant.id = :v" : "a.productVariant is null";
	}

	private static Query bind(Query q, ShoppingCartItem item) {
		q.setParameter("p", item.getProductId());
		if (item.getVariant() != null) {
			q.setParameter("v", item.getVariant());
		}
		return q;
	}

	private static OrderStatusHistory history(Order order, OrderStatus status, String comments) {
		OrderStatusHistory h = new OrderStatusHistory();
		h.setOrder(order);
		h.setStatus(status);
		h.setDateAdded(new Date());
		h.setCustomerNotified(0);
		if (comments != null) {
			h.setComments(comments);
		}
		return h;
	}
}
