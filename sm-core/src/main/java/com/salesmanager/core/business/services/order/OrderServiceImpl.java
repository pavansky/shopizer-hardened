package com.salesmanager.core.business.services.order;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.modules.order.InvoiceModule;
import com.salesmanager.core.business.repositories.order.OrderRepository;
import com.salesmanager.core.business.services.catalog.product.ProductService;
import com.salesmanager.core.business.services.common.generic.SalesManagerEntityServiceImpl;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.ordertotal.OrderTotalService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.business.services.payments.attempt.PaymentAttemptService;
import com.salesmanager.core.business.modules.integration.payment.impl.PaymentActionRequiredException;
import com.salesmanager.core.business.modules.integration.payment.impl.PaymentIntentInUseException;
import com.salesmanager.core.business.modules.integration.payment.impl.Stripe3Payment;
import com.salesmanager.core.business.services.shipping.ShippingService;
import com.salesmanager.core.business.services.shoppingcart.ShoppingCartService;
import com.salesmanager.core.business.services.tax.TaxService;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.catalog.product.price.FinalPrice;
import com.salesmanager.core.model.common.UserContext;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.OrderCriteria;
import com.salesmanager.core.model.order.OrderList;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.order.OrderSummaryType;
import com.salesmanager.core.model.order.OrderTotal;
import com.salesmanager.core.model.order.OrderTotalSummary;
import com.salesmanager.core.model.order.OrderTotalType;
import com.salesmanager.core.model.order.OrderTotalVariation;
import com.salesmanager.core.model.order.OrderValueType;
import com.salesmanager.core.model.order.orderproduct.OrderProduct;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentAttempt;
import com.salesmanager.core.model.payments.PaymentAttemptStatus;
import com.salesmanager.core.modules.integration.PaymentOutcomeUnknownException;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.shipping.ShippingConfiguration;
import com.salesmanager.core.model.shoppingcart.ShoppingCart;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.tax.TaxItem;

@Service("orderService")
public class OrderServiceImpl  extends SalesManagerEntityServiceImpl<Long, Order> implements OrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Inject
    private InvoiceModule invoiceModule;

    @Inject
    private ShippingService shippingService;

    @Inject
    private PaymentService paymentService;

    @Inject
    private ProductService productService;

    @Inject
    private TaxService taxService;

    @Inject
    private CustomerService customerService;

    @Inject
    private ShoppingCartService shoppingCartService;

    @Inject
    private TransactionService transactionService;

    @Inject
    private OrderTotalService orderTotalService;

    @Inject
    private OrderCheckoutSteps orderCheckoutSteps;

    @Inject
    private PaymentAttemptService paymentAttemptService;

    private final OrderRepository orderRepository;

    @Inject
    public OrderServiceImpl(OrderRepository orderRepository) {
        super(orderRepository);
        this.orderRepository = orderRepository;
    }

    @Override
    public void addOrderStatusHistory(Order order, OrderStatusHistory history) throws ServiceException {
        order.getOrderHistory().add(history);
        history.setOrder(order);
        update(order);
    }

    @Override
    public Order processOrder(Order order, Customer customer, List<ShoppingCartItem> items, OrderTotalSummary summary, Payment payment, MerchantStore store) throws ServiceException {

    	return process(order, customer, items, summary, payment, null, store);
    }

    @Override
    public Order processOrder(Order order, Customer customer, List<ShoppingCartItem> items, OrderTotalSummary summary, Payment payment, Transaction transaction, MerchantStore store) throws ServiceException {
    	return process(order, customer, items, summary, payment, transaction, store);
    }

	private Order process(Order order, Customer customer, List<ShoppingCartItem> items, OrderTotalSummary summary, Payment payment, Transaction transaction, MerchantStore store) throws ServiceException {


    	Validate.notNull(order, "Order cannot be null");
    	Validate.notNull(customer, "Customer cannot be null (even if anonymous order)");
    	Validate.notEmpty(items, "ShoppingCart items cannot be null");
    	Validate.notNull(payment, "Payment cannot be null");
    	Validate.notNull(store, "MerchantStore cannot be null");
    	Validate.notNull(summary, "Order total Summary cannot be null");

    	UserContext context = UserContext.getCurrentInstance();
    	if(context != null) {
    		String ipAddress = context.getIpAddress();
    		if(!StringUtils.isBlank(ipAddress)) {
    			order.setIpAddress(ipAddress);
    		}
    	}


    	// 1. committed step: reserve inventory and insert the order in PAYMENT_PENDING.
    	//    An inventory shortfall fails here, before anything is charged.
    	OrderStatus finalStatus = order.getStatus();
    	if (finalStatus == null || finalStatus == OrderStatus.PAYMENT_PENDING) {
    		finalStatus = OrderStatus.ORDERED;
    	}
    	// A checkout retried after a 3-D Secure challenge carries the same PaymentIntent:
    	// continue the order kept open for it instead of reserving the stock again.
    	PaymentAttempt attempt = null;
    	Resume resume = attemptAwaitingCustomerAction(order, items, payment, store);
    	if (resume != null) {
    		// the waiting order was placed from this same cart with the same lines and
    		// total, so the current items are the lines its stock was reserved for
    		attempt = resume.attempt;
    		order = resume.order;
    		LOGGER.info("Order {} resumes payment attempt {} after customer action", order.getId(),
    				attempt.getAttemptRef());
    	} else {
    		order = orderCheckoutSteps.createPendingOrder(order, customer, items, transaction);

    		// 2. committed step: write-ahead record of the gateway call. Its reference goes to
    		//    the gateway, so a charge whose Transaction is never stored can still be found.
    		try {
    			attempt = orderCheckoutSteps.beginPaymentAttempt(newAttempt(order, payment, store));
    		} catch (RuntimeException e) {
    			// Nothing was sent to the gateway: fail the order now rather than waiting for the reconciler.
    			LOGGER.error("Could not record the payment attempt of order " + order.getId() + "; not charging", e);
    			orderCheckoutSteps.markPaymentFailed(order.getId(), items, "payment attempt not recorded", null);
    			order.setStatus(OrderStatus.PAYMENT_FAILED);
    			throw new ServiceException(ServiceException.EXCEPTION_ERROR, "Payment could not be started");
    		}
    	}
    	if (payment.getPaymentMetaData() == null) {
    		payment.setPaymentMetaData(new HashMap<>());
    	}
    	payment.getPaymentMetaData().put(PaymentAttempt.META_ATTEMPT_REF, attempt.getAttemptRef());
    	if (StringUtils.isNotBlank(order.getShoppingCartCode())) {
    		// lets the module check that a PaymentIntent was started for this cart
    		payment.getPaymentMetaData().put(Stripe3Payment.META_CART_CODE, order.getShoppingCartCode());
    	}

    	// 3. charge (outside of any write that could still roll back the order)
    	Transaction processTransaction;
    	try {
    		processTransaction = paymentService.processPayment(customer, store, payment, items, order);
    	} catch (Exception e) {
    		PaymentActionRequiredException actionRequired = PaymentActionRequiredException.in(e);
    		if (actionRequired != null && keepOpenForCustomerAction(attempt, actionRequired)) {
    			// Not paid yet: the customer finishes 3-D Secure (or picks another card) in the
    			// browser. The order stays PAYMENT_PENDING with its stock and the attempt stays
    			// open; the retried checkout continues it. No money moved.
    			LOGGER.info("Order {} waits for customer action on PaymentIntent {} ({})", order.getId(),
    					actionRequired.getPaymentIntentId(), actionRequired.getStatus());
    			actionRequired.setOrderId(order.getId());
    			throw actionRequired;
    		}
    		if (isOutcomeUnknown(e)) {
    			// Money may have been taken. Keep the order PAYMENT_PENDING and its stock
    			// reserved; the reconciler asks the gateway and settles it.
    			LOGGER.error("Payment outcome unknown for order " + order.getId() + " (attempt "
    					+ attempt.getAttemptRef() + "); left in PAYMENT_PENDING for reconciliation", e);
    			try {
    				orderCheckoutSteps.markPaymentOutcomeUnknown(attempt.getId(), describe(e));
    			} catch (RuntimeException markFailure) {
    				LOGGER.error("Could not mark payment attempt " + attempt.getAttemptRef() + " UNKNOWN", markFailure);
    			}
    			throw new PaymentConfirmationPendingException(order.getId(), e);
    		}
    		LOGGER.warn("Payment failed for order " + order.getId(), e);
    		try {
    			orderCheckoutSteps.markPaymentFailed(order.getId(), items, e.getMessage(), attempt.getId());
    		} catch (RuntimeException markFailure) {
    			LOGGER.error("Could not mark order " + order.getId() + " as PAYMENT_FAILED", markFailure);
    		}
    		order.setStatus(OrderStatus.PAYMENT_FAILED);
    		if (e instanceof ServiceException) {
    			throw (ServiceException) e;
    		}
    		throw new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, e.getMessage());
    	}

    	// 4. committed step: mark the order paid, record the payment transaction and the
    	//    attempt outcome. If this fails the order stays PAYMENT_PENDING with a captured
    	//    charge and must be reconciled; it is never silently dropped.
    	try {
    		orderCheckoutSteps.markPaymentSucceeded(order.getId(), finalStatus, processTransaction, attempt.getId());
    	} catch (ServiceException | RuntimeException e) {
    		LOGGER.error("Order " + order.getId() + " was charged but could not be marked " + finalStatus
    				+ "; left in PAYMENT_PENDING for reconciliation", e);
    		throw new OrderChargedNotRecordedException(order.getId(), e);
    	}
    	order.setStatus(finalStatus);

    	return order;
    }

    /** Failure reason of an attempt kept open while the customer completes the payment in the browser. */
    static final String AWAITING_CUSTOMER_ACTION = "awaiting customer action: ";

    /**
     * Marks the attempt as waiting for the customer. It stays INITIATED, so the
     * reconciler still asks Stripe about it (an abandoned requires_payment_method
     * intent fails the order after the grace period, an abandoned challenge moves it
     * to PAYMENT_REVIEW after a day). Returns false when the mark could not be
     * written; the order is then failed as before, so no second open order can
     * later use the same PaymentIntent.
     */
    private boolean keepOpenForCustomerAction(PaymentAttempt attempt, PaymentActionRequiredException actionRequired) {
    	try {
    		return paymentAttemptService.update(attempt.getId(), PaymentAttemptStatus.INITIATED,
    				actionRequired.getPaymentIntentId(), AWAITING_CUSTOMER_ACTION + actionRequired.getStatus());
    	} catch (RuntimeException e) {
    		LOGGER.error("Could not keep payment attempt " + attempt.getAttemptRef() + " open for customer action", e);
    		return false;
    	}
    }

    /** A waiting order and its attempt that a retried checkout continues. */
    private static final class Resume {
    	final PaymentAttempt attempt;
    	final Order order;

    	Resume(PaymentAttempt attempt, Order order) {
    		this.attempt = attempt;
    		this.order = order;
    	}
    }

    /**
     * The open attempt (and its order) of an earlier checkout that is waiting for the
     * customer to act on the same PaymentIntent, or null.
     *
     * The waiting order must come from the same cart as this checkout; otherwise the
     * checkout is refused with {@link PaymentIntentInUseException} and the waiting
     * order is not touched, so nobody can take over or cancel another customer's
     * order with its PaymentIntent id. From the same cart, it is resumed only while
     * still PAYMENT_PENDING with the same total and the same lines (sku and quantity).
     * Otherwise it is failed (its own stock returned) before a new order is placed, so
     * at most one open order ever uses a PaymentIntent and the reconciler cannot
     * confirm two orders for one payment.
     */
    private Resume attemptAwaitingCustomerAction(Order order, List<ShoppingCartItem> items, Payment payment,
    		MerchantStore store) throws ServiceException {
    	String intentId = knownGatewayRef(payment);
    	if (intentId == null) {
    		return null;
    	}
    	PaymentAttempt previous = paymentAttemptService.findByGatewayRef(intentId);
    	if (previous == null || previous.getStatus() != PaymentAttemptStatus.INITIATED
    			|| !StringUtils.startsWith(previous.getFailureReason(), AWAITING_CUSTOMER_ACTION)
    			|| !store.getId().equals(previous.getMerchantId())) {
    		return null;
    	}
    	// fetched with its lines, scoped to the store
    	Order waiting = orderRepository.findOne(previous.getOrderId(), store.getId());
    	if (waiting != null && !sameCart(waiting, order)) {
    		LOGGER.warn("Checkout of cart {} refused: PaymentIntent {} is held by order {} of another cart",
    				order.getShoppingCartCode(), intentId, waiting.getId());
    		throw new PaymentIntentInUseException(intentId);
    	}
    	if (waiting != null && waiting.getStatus() == OrderStatus.PAYMENT_PENDING && waiting.getTotal() != null
    			&& order.getTotal() != null && waiting.getTotal().compareTo(order.getTotal()) == 0
    			&& sameLines(waiting, items)) {
    		return new Resume(previous, waiting);
    	}
    	LOGGER.info("Order {} waiting on PaymentIntent {} is superseded by a new checkout", previous.getOrderId(),
    			intentId);
    	orderCheckoutSteps.reconcileUnpaidOrder(previous.getOrderId(), "superseded by a new checkout",
    			previous.getId());
    	return null;
    }

    private static boolean sameCart(Order waiting, Order order) {
    	return StringUtils.isNotBlank(order.getShoppingCartCode())
    			&& order.getShoppingCartCode().equals(waiting.getShoppingCartCode());
    }

    /** True when the cart lines are the order's lines: the same skus with the same quantities. */
    static boolean sameLines(Order waiting, List<ShoppingCartItem> items) {
    	Map<String, Integer> ordered = new HashMap<>();
    	for (OrderProduct product : waiting.getOrderProducts()) {
    		ordered.merge(StringUtils.defaultString(product.getSku()), product.getProductQuantity(), Integer::sum);
    	}
    	Map<String, Integer> inCart = new HashMap<>();
    	for (ShoppingCartItem item : items) {
    		inCart.merge(StringUtils.defaultString(item.getSku()),
    				item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
    	}
    	return ordered.equals(inCart);
    }

    private static PaymentAttempt newAttempt(Order order, Payment payment, MerchantStore store) {
    	PaymentAttempt attempt = new PaymentAttempt();
    	attempt.setAttemptRef(UUID.randomUUID().toString());
    	attempt.setOrderId(order.getId());
    	attempt.setMerchantId(store.getId());
    	attempt.setModuleCode(payment.getModuleName());
    	attempt.setAmount(order.getTotal());
    	if (store.getCurrency() != null) {
    		attempt.setCurrency(store.getCurrency().getCode());
    	}
    	attempt.setGatewayRef(knownGatewayRef(payment));
    	return attempt;
    }

    /**
     * Gateway reference known before the call. For Stripe PaymentIntents the
     * client sends the intent id as the token, so a lost response can still be
     * looked up by it.
     */
    static String knownGatewayRef(Payment payment) {
    	if (!"stripe3".equals(payment.getModuleName()) || payment.getPaymentMetaData() == null) {
    		return null;
    	}
    	String token = payment.getPaymentMetaData().get("stripe_token");
    	if (StringUtils.isBlank(token)) {
    		token = payment.getPaymentMetaData().get("paymentToken");
    	}
    	return StringUtils.startsWith(token, "pi_") ? token : null;
    }

    /**
     * True when the failure does not tell whether money was taken: the module
     * reported an unknown outcome, an IO error or timeout is in the cause chain,
     * or an unexpected runtime error escaped the module. A ServiceException
     * (including IntegrationException) is a definitive decline or a refusal
     * before any money moved.
     */
    static boolean isOutcomeUnknown(Throwable e) {
    	for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
    		if (t instanceof PaymentOutcomeUnknownException || t instanceof java.io.IOException
    				|| t instanceof java.util.concurrent.TimeoutException) {
    			return true;
    		}
    	}
    	return !(e instanceof ServiceException);
    }

    private static String describe(Throwable e) {
    	String message = e.getMessage();
    	return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private OrderTotalSummary caculateOrder(OrderSummary summary, Customer customer, final MerchantStore store, final Language language) throws Exception {

        OrderTotalSummary totalSummary = new OrderTotalSummary();
        List<OrderTotal> orderTotals = new ArrayList<OrderTotal>();
        Map<String,OrderTotal> otherPricesTotals = new HashMap<String,OrderTotal>();

        ShippingConfiguration shippingConfiguration = null;

        BigDecimal grandTotal = new BigDecimal(0);
        grandTotal.setScale(2, RoundingMode.HALF_UP);

        //price by item
        /**
         * qty * price
         * subtotal
         */
        BigDecimal subTotal = new BigDecimal(0);
        subTotal.setScale(2, RoundingMode.HALF_UP);
        //order level reductions (coupons), taken off the tax base as well
        BigDecimal discount = BigDecimal.ZERO;
        for(ShoppingCartItem item : summary.getProducts()) {

            BigDecimal st = item.getItemPrice().multiply(new BigDecimal(item.getQuantity()));
            item.setSubTotal(st);
            subTotal = subTotal.add(st);
            //Other prices
            FinalPrice finalPrice = item.getFinalPrice();
            if(finalPrice!=null) {
                List<FinalPrice> otherPrices = finalPrice.getAdditionalPrices();
                if(otherPrices!=null) {
                    for(FinalPrice price : otherPrices) {
                        if(!price.isDefaultPrice()) {
                            OrderTotal itemSubTotal = otherPricesTotals.get(price.getProductPrice().getCode());

                            if(itemSubTotal==null) {
                                itemSubTotal = new OrderTotal();
                                itemSubTotal.setModule(Constants.OT_ITEM_PRICE_MODULE_CODE);
                                itemSubTotal.setTitle(Constants.OT_ITEM_PRICE_MODULE_CODE);
                                itemSubTotal.setOrderTotalCode(price.getProductPrice().getCode());
                                itemSubTotal.setOrderTotalType(OrderTotalType.PRODUCT);
                                itemSubTotal.setSortOrder(0);
                                otherPricesTotals.put(price.getProductPrice().getCode(), itemSubTotal);
                            }

                            BigDecimal orderTotalValue = itemSubTotal.getValue();
                            if(orderTotalValue==null) {
                                orderTotalValue = new BigDecimal(0);
                                orderTotalValue.setScale(2, RoundingMode.HALF_UP);
                            }

                            orderTotalValue = orderTotalValue.add(price.getFinalPrice());
                            itemSubTotal.setValue(orderTotalValue);
                            if(price.getProductPrice().getProductPriceType().name().equals(OrderValueType.ONE_TIME)) {
                                subTotal = subTotal.add(price.getFinalPrice());
                            }
                        }
                    }
                }
            }
        }

        //only in order page, otherwise invokes too many processing
        if(
        		OrderSummaryType.ORDERTOTAL.name().equals(summary.getOrderSummaryType().name()) ||
        		OrderSummaryType.SHOPPINGCART.name().equals(summary.getOrderSummaryType().name())

        		) {

	        //Post processing order total variation modules for sub total calculation - drools, custom modules
	        //may affect the sub total
	        OrderTotalVariation orderTotalVariation = orderTotalService.findOrderTotalVariation(summary, customer, store, language);

	        int currentCount = 10;

	        if(CollectionUtils.isNotEmpty(orderTotalVariation.getVariations())) {
	        	for(OrderTotal variation : orderTotalVariation.getVariations()) {
	        		if(variation.getValue()==null) {
	        			continue;
	        		}
	        		//modules may compute the reduction from a double, keep money at 2 decimals
	        		variation.setValue(variation.getValue().setScale(2, RoundingMode.HALF_UP));
	        		variation.setSortOrder(currentCount++);
	        		orderTotals.add(variation);
	        		subTotal = subTotal.subtract(variation.getValue());
	        		discount = discount.add(variation.getValue());
	        	}
	        }

        }


        totalSummary.setSubTotal(subTotal);
        grandTotal=grandTotal.add(subTotal);

        OrderTotal orderTotalSubTotal = new OrderTotal();
        orderTotalSubTotal.setModule(Constants.OT_SUBTOTAL_MODULE_CODE);
        orderTotalSubTotal.setOrderTotalType(OrderTotalType.SUBTOTAL);
        orderTotalSubTotal.setOrderTotalCode("order.total.subtotal");
        orderTotalSubTotal.setTitle(Constants.OT_SUBTOTAL_MODULE_CODE);
        orderTotalSubTotal.setSortOrder(5);
        orderTotalSubTotal.setValue(subTotal);

        orderTotals.add(orderTotalSubTotal);


        //shipping
        if(summary.getShippingSummary()!=null) {


	            OrderTotal shippingSubTotal = new OrderTotal();
	            shippingSubTotal.setModule(Constants.OT_SHIPPING_MODULE_CODE);
	            shippingSubTotal.setOrderTotalType(OrderTotalType.SHIPPING);
	            shippingSubTotal.setOrderTotalCode("order.total.shipping");
	            shippingSubTotal.setTitle(Constants.OT_SHIPPING_MODULE_CODE);
	            shippingSubTotal.setSortOrder(100);

	            orderTotals.add(shippingSubTotal);

            if(!summary.getShippingSummary().isFreeShipping()) {
                shippingSubTotal.setValue(summary.getShippingSummary().getShipping());
                grandTotal=grandTotal.add(summary.getShippingSummary().getShipping());
            } else {
                shippingSubTotal.setValue(new BigDecimal(0));
                grandTotal=grandTotal.add(new BigDecimal(0));
            }

            //check handling fees
            shippingConfiguration = shippingService.getShippingConfiguration(store);
            if(summary.getShippingSummary().getHandling()!=null && summary.getShippingSummary().getHandling().doubleValue()>0) {
                if(shippingConfiguration.getHandlingFees()!=null && shippingConfiguration.getHandlingFees().doubleValue()>0) {
                    OrderTotal handlingubTotal = new OrderTotal();
                    handlingubTotal.setModule(Constants.OT_HANDLING_MODULE_CODE);
                    handlingubTotal.setOrderTotalType(OrderTotalType.HANDLING);
                    handlingubTotal.setOrderTotalCode("order.total.handling");
                    handlingubTotal.setTitle(Constants.OT_HANDLING_MODULE_CODE);
                    //handlingubTotal.setText("order.total.handling");
                    handlingubTotal.setSortOrder(120);
                    handlingubTotal.setValue(summary.getShippingSummary().getHandling());
                    orderTotals.add(handlingubTotal);
                    grandTotal=grandTotal.add(summary.getShippingSummary().getHandling());
                }
            }
        }

        //tax
        List<TaxItem> taxes = taxService.calculateTax(summary, customer, store, language, discount);
        if(taxes!=null && taxes.size()>0) {
        	BigDecimal totalTaxes = new BigDecimal(0);
        	totalTaxes.setScale(2, RoundingMode.HALF_UP);
            int taxCount = 200;
            for(TaxItem tax : taxes) {

                OrderTotal taxLine = new OrderTotal();
                taxLine.setModule(Constants.OT_TAX_MODULE_CODE);
                taxLine.setOrderTotalType(OrderTotalType.TAX);
                taxLine.setOrderTotalCode(tax.getLabel());
                taxLine.setSortOrder(taxCount);
                taxLine.setTitle(Constants.OT_TAX_MODULE_CODE);
                taxLine.setText(tax.getLabel());
                taxLine.setValue(tax.getItemPrice());

                totalTaxes = totalTaxes.add(tax.getItemPrice());
                orderTotals.add(taxLine);
                //grandTotal=grandTotal.add(tax.getItemPrice());

                taxCount ++;

            }
            grandTotal = grandTotal.add(totalTaxes);
            totalSummary.setTaxTotal(totalTaxes);
        }

        // grand total
        OrderTotal orderTotal = new OrderTotal();
        orderTotal.setModule(Constants.OT_TOTAL_MODULE_CODE);
        orderTotal.setOrderTotalType(OrderTotalType.TOTAL);
        orderTotal.setOrderTotalCode("order.total.total");
        orderTotal.setTitle(Constants.OT_TOTAL_MODULE_CODE);
        //orderTotal.setText("order.total.total");
        orderTotal.setSortOrder(500);
        orderTotal.setValue(grandTotal);
        orderTotals.add(orderTotal);

        totalSummary.setTotal(grandTotal);
        totalSummary.setTotals(orderTotals);
        return totalSummary;

    }


    @Override
    public OrderTotalSummary caculateOrderTotal(final OrderSummary orderSummary, final Customer customer, final MerchantStore store, final Language language) throws ServiceException {
        Validate.notNull(orderSummary,"Order summary cannot be null");
        Validate.notNull(orderSummary.getProducts(),"Order summary.products cannot be null");
        Validate.notNull(store,"MerchantStore cannot be null");
        Validate.notNull(customer,"Customer cannot be null");

        try {
            return caculateOrder(orderSummary, customer, store, language);
        } catch (Exception e) {
            throw new ServiceException(e);
        }

    }



    @Override
    public OrderTotalSummary caculateOrderTotal(final OrderSummary orderSummary, final MerchantStore store, final Language language) throws ServiceException {
        Validate.notNull(orderSummary,"Order summary cannot be null");
        Validate.notNull(orderSummary.getProducts(),"Order summary.products cannot be null");
        Validate.notNull(store,"MerchantStore cannot be null");

        try {
            return caculateOrder(orderSummary, null, store, language);
        } catch (Exception e) {
            throw new ServiceException(e);
        }

    }

    private OrderTotalSummary caculateShoppingCart( ShoppingCart shoppingCart, final Customer customer, final MerchantStore store, final Language language) throws Exception {


    	OrderSummary orderSummary = new OrderSummary();
    	orderSummary.setOrderSummaryType(OrderSummaryType.SHOPPINGCART);

    	if(!StringUtils.isBlank(shoppingCart.getPromoCode())) {
    		Date promoDateAdded = shoppingCart.getPromoAdded();//promo valid 1 day
    		if(promoDateAdded == null) {
    			promoDateAdded = new Date();
    		}
    		Instant instant = promoDateAdded.toInstant();
    		ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
    		LocalDate date = zdt.toLocalDate();
    		//date added < date + 1 day
    		LocalDate tomorrow = LocalDate.now().plusDays(1);
    		if(date.isBefore(tomorrow)) {
    			orderSummary.setPromoCode(shoppingCart.getPromoCode());
    		} else {
    			//clear promo
    			shoppingCart.setPromoCode(null);
    			shoppingCartService.saveOrUpdate(shoppingCart);
    		}
    	}

    	List<ShoppingCartItem> itemList = new ArrayList<ShoppingCartItem>(shoppingCart.getLineItems());
    	//filter out unavailable
    	itemList = itemList.stream().filter(p -> p.getProduct().isAvailable()).collect(Collectors.toList());
    	orderSummary.setProducts(itemList);


    	return caculateOrder(orderSummary, customer, store, language);

    }


    /**
     * <p>Method will be used to calculate Shopping cart total as well will update price for each
     * line items.
     * </p>
     * @param shoppingCart
     * @param customer
     * @param store
     * @param language
     * @return {@link OrderTotalSummary}
     * @throws ServiceException
     *
     */
    @Override
    public OrderTotalSummary calculateShoppingCartTotal(
                                                        final ShoppingCart shoppingCart, final Customer customer, final MerchantStore store,
                                                        final Language language) throws ServiceException {
        Validate.notNull(shoppingCart,"Order summary cannot be null");
        Validate.notNull(customer,"Customery cannot be null");
        Validate.notNull(store,"MerchantStore cannot be null.");
        try {
            return caculateShoppingCart(shoppingCart, customer, store, language);
        } catch (Exception e) {
            LOGGER.error( "Error while calculating shopping cart total" +e );
            throw new ServiceException(e);
        }

    }




    /**
     * <p>Method will be used to calculate Shopping cart total as well will update price for each
     * line items.
     * </p>
     * @param shoppingCart
     * @param store
     * @param language
     * @return {@link OrderTotalSummary}
     * @throws ServiceException
     *
     */
    @Override
    public OrderTotalSummary calculateShoppingCartTotal(
                                                        final ShoppingCart shoppingCart, final MerchantStore store, final Language language)
                                                                        throws ServiceException {
        Validate.notNull(shoppingCart,"Order summary cannot be null");
        Validate.notNull(store,"MerchantStore cannot be null");

        try {
            return caculateShoppingCart(shoppingCart, null, store, language);
        } catch (Exception e) {
            LOGGER.error( "Error while calculating shopping cart total" +e );
            throw new ServiceException(e);
        }
    }

    @Override
    public void delete(final Order order) throws ServiceException {


        super.delete(order);
    }


    @Override
    public ByteArrayOutputStream generateInvoice(final MerchantStore store, final Order order, final Language language) throws ServiceException {

        Validate.notNull(order.getOrderProducts(),"Order products cannot be null");
        Validate.notNull(order.getOrderTotal(),"Order totals cannot be null");

        try {
            return invoiceModule.createInvoice(store, order, language);
        } catch(Exception e) {
            throw new ServiceException(e);
        }



    }

    @Override
    public Order getOrder(final Long orderId, MerchantStore store ) {
    	Validate.notNull(orderId, "Order id cannot be null");
    	Validate.notNull(store, "Store cannot be null");
        return orderRepository.findOne(orderId, store.getId());
    }


    /** legacy **/
    @Override
    public OrderList listByStore(final MerchantStore store, final OrderCriteria criteria) {
        return orderRepository.listByStore(store, criteria);
    }

    @Override
    public OrderList getOrders(final OrderCriteria criteria, MerchantStore store) {
        return orderRepository.listOrders(store, criteria);
    }


    @Override
    public void saveOrUpdate(final Order order) throws ServiceException {

        if(order.getId()!=null && order.getId()>0) {
            LOGGER.debug("Updating Order");
            super.update(order);

        } else {
            LOGGER.debug("Creating Order");
            super.create(order);

        }
    }

	@Override
	public boolean hasDownloadFiles(Order order) throws ServiceException {

		Validate.notNull(order,"Order cannot be null");
		Validate.notNull(order.getOrderProducts(),"Order products cannot be null");
		Validate.notEmpty(order.getOrderProducts(),"Order products cannot be empty");

		boolean hasDownloads = false;
		for(OrderProduct orderProduct : order.getOrderProducts()) {

			if(CollectionUtils.isNotEmpty(orderProduct.getDownloads())) {
				hasDownloads = true;
				break;
			}
		}

		return hasDownloads;
	}

	@Override
	public List<Order> getCapturableOrders(MerchantStore store, Date startDate, Date endDate) throws ServiceException {

		List<Transaction> transactions = transactionService.listTransactions(startDate, endDate);

		List<Order> returnOrders = null;

		if(!CollectionUtils.isEmpty(transactions)) {

			returnOrders = new ArrayList<Order>();

			//order id
			Map<Long,Order> preAuthOrders = new HashMap<Long,Order> ();
			//order id
			Map<Long,List<Transaction>> processingTransactions = new HashMap<Long,List<Transaction>> ();

			for(Transaction trx : transactions) {
				Order order = trx.getOrder();
				if(TransactionType.AUTHORIZE.name().equals(trx.getTransactionType().name())) {
					preAuthOrders.put(order.getId(), order);
				}

				//put transaction
				List<Transaction> listTransactions = null;
				if(processingTransactions.containsKey(order.getId())) {
					listTransactions = processingTransactions.get(order.getId());
				} else {
					listTransactions = new ArrayList<Transaction>();
					processingTransactions.put(order.getId(), listTransactions);
				}
				listTransactions.add(trx);
			}

			//should have when captured
			/**
			 * Order id  Transaction type
			 * 1          AUTHORIZE
			 * 1          CAPTURE
			 */

			//should have when not captured
			/**
			 * Order id  Transaction type
			 * 2          AUTHORIZE
			 */

			for(Long orderId : processingTransactions.keySet()) {

				List<Transaction> trx = processingTransactions.get(orderId);
				if(CollectionUtils.isNotEmpty(trx)) {

					boolean capturable = true;
					for(Transaction t : trx) {

						if(TransactionType.CAPTURE.name().equals(t.getTransactionType().name())) {
							capturable = false;
						} else if(TransactionType.AUTHORIZECAPTURE.name().equals(t.getTransactionType().name())) {
							capturable = false;
						} else if(TransactionType.REFUND.name().equals(t.getTransactionType().name())) {
							capturable = false;
						}

					}

					if(capturable) {
						Order o = preAuthOrders.get(orderId);
						returnOrders.add(o);
					}

				}


			}
		}

		return returnOrders;
	}



}
