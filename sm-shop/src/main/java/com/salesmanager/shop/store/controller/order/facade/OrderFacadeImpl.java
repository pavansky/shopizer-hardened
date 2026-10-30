package com.salesmanager.shop.store.controller.order.facade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.exception.ConversionException;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.catalog.pricing.PricingService;
import com.salesmanager.core.business.services.catalog.product.ProductService;
import com.salesmanager.core.business.services.catalog.product.attribute.ProductAttributeService;
import com.salesmanager.core.business.services.catalog.product.file.DigitalProductService;
import com.salesmanager.core.business.services.order.OrderChargedNotRecordedException;
import com.salesmanager.core.business.services.order.OrderCheckoutSteps;
import com.salesmanager.core.business.services.order.OrderCommandRefusedException;
import com.salesmanager.core.business.services.order.OrderCommandService;
import com.salesmanager.core.business.services.order.reconciliation.PendingOrderReconciler;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.order.IllegalOrderStatusTransitionException;
import com.salesmanager.core.business.services.order.OrderStatusTransitions;
import com.salesmanager.core.business.services.order.idempotency.IdempotencyConflictException;
import com.salesmanager.core.business.services.order.idempotency.OrderIdempotencyService;
import com.salesmanager.core.model.order.idempotency.OrderIdempotencyKey;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.payments.TransactionService;
import com.salesmanager.core.business.services.reference.country.CountryService;
import com.salesmanager.core.business.services.reference.zone.ZoneService;
import com.salesmanager.core.business.services.shipping.ShippingQuoteService;
import com.salesmanager.core.business.services.shipping.ShippingService;
import com.salesmanager.core.business.services.shoppingcart.ShoppingCartService;
import com.salesmanager.core.business.utils.CoreConfiguration;
import com.salesmanager.core.business.utils.CreditCardUtils;
import com.salesmanager.core.business.utils.ProductPriceUtils;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.availability.ProductAvailability;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.core.model.common.Delivery;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.OrderCriteria;
import com.salesmanager.core.model.order.OrderList;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.order.OrderTotalSummary;
import com.salesmanager.core.model.order.attributes.OrderAttribute;
import com.salesmanager.core.model.order.orderproduct.OrderProduct;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.order.orderstatus.OrderStatusHistory;
import com.salesmanager.core.model.order.payment.CreditCard;
import com.salesmanager.core.model.payments.CreditCardPayment;
import com.salesmanager.core.model.payments.CreditCardType;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.PaymentType;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.shipping.ShippingProduct;
import com.salesmanager.core.model.shipping.ShippingQuote;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCart;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.ReadableCustomer;
import com.salesmanager.shop.model.customer.address.Address;
import com.salesmanager.shop.model.order.OrderEntity;
import com.salesmanager.shop.model.order.PersistableOrderProduct;
import com.salesmanager.shop.model.order.ReadableOrderLifecycle;
import com.salesmanager.shop.model.order.ReadableOrderProduct;
import com.salesmanager.shop.model.order.ShopOrder;
import com.salesmanager.shop.model.order.history.PersistableOrderStatusHistory;
import com.salesmanager.shop.model.order.history.ReadableOrderStatusHistory;
import com.salesmanager.shop.model.order.total.OrderTotal;
import com.salesmanager.shop.model.order.transaction.ReadableTransaction;
import com.salesmanager.shop.populator.customer.CustomerPopulator;
import com.salesmanager.shop.populator.customer.PersistableCustomerPopulator;
import com.salesmanager.shop.populator.order.OrderProductPopulator;
import com.salesmanager.shop.populator.order.PersistableOrderApiPopulator;
import com.salesmanager.shop.populator.order.ReadableOrderPopulator;
import com.salesmanager.shop.populator.order.ReadableOrderProductPopulator;
import com.salesmanager.shop.populator.order.ShoppingCartItemPopulator;
import com.salesmanager.shop.populator.order.transaction.PersistablePaymentPopulator;
import com.salesmanager.shop.populator.order.transaction.ReadableTransactionPopulator;
import com.salesmanager.shop.store.api.exception.ConflictException;
import com.salesmanager.shop.store.api.exception.GenericRuntimeException;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.exception.RestApiException;
import com.salesmanager.shop.store.api.exception.ServiceRuntimeException;
import com.salesmanager.shop.store.controller.customer.facade.CustomerFacade;
import com.salesmanager.shop.store.controller.shoppingCart.facade.ShoppingCartFacade;
import com.salesmanager.shop.utils.DateUtil;
import com.salesmanager.shop.utils.EmailTemplatesUtils;
import com.salesmanager.shop.utils.ImageFilePath;
import com.salesmanager.shop.utils.LabelUtils;
import com.salesmanager.shop.utils.LocaleUtils;

@Service("orderFacade")
public class OrderFacadeImpl implements OrderFacade {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderFacadeImpl.class);

	@Inject
	private OrderService orderService;

	@Inject
	private OrderIdempotencyService orderIdempotencyService;
	@Inject
	private ProductService productService;
	@Inject
	private ProductAttributeService productAttributeService;
	@Inject
	private ShoppingCartService shoppingCartService;
	@Inject
	private DigitalProductService digitalProductService;
	@Inject
	private ShippingService shippingService;
	@Inject
	private CustomerFacade customerFacade;
	@Inject
	private PricingService pricingService;
	@Inject
	private ShoppingCartFacade shoppingCartFacade;
	@Inject
	private ShippingQuoteService shippingQuoteService;
	@Inject
	private CoreConfiguration coreConfiguration;
	@Inject
	private PaymentService paymentService;
	@Inject
	private CountryService countryService;
	@Inject
	private ZoneService zoneService;


	@Autowired
	private PersistableOrderApiPopulator persistableOrderApiPopulator;

	@Autowired
	private ReadableOrderPopulator readableOrderPopulator;

	@Autowired
	private CustomerPopulator customerPopulator;

	@Autowired
	private TransactionService transactionService;

	@Inject
	private CustomerService customerService;

	@Inject
	private EmailTemplatesUtils emailTemplatesUtils;

	@Inject
	private OrderCommandService orderCommandService;

	@Inject
	private OrderCheckoutSteps orderCheckoutSteps;

	@Inject
	private PendingOrderReconciler pendingOrderReconciler;

	@Inject
	private LabelUtils messages;
	
	@Autowired
	private ProductPriceUtils productPriceUtils;

	@Inject
	@Qualifier("img")
	private ImageFilePath imageUtils;

	@Override
	public ShopOrder initializeOrder(MerchantStore store, Customer customer, ShoppingCart shoppingCart,
			Language language) throws Exception {

		// assert not null shopping cart items

		ShopOrder order = new ShopOrder();

		OrderStatus orderStatus = OrderStatus.ORDERED;
		order.setOrderStatus(orderStatus);

		if (customer == null) {
			customer = this.initEmptyCustomer(store);
		}

		PersistableCustomer persistableCustomer = persistableCustomer(customer, store, language);
		order.setCustomer(persistableCustomer);

		// keep list of shopping cart items for core price calculation
		List<ShoppingCartItem> items = new ArrayList<ShoppingCartItem>(shoppingCart.getLineItems());
		order.setShoppingCartItems(items);

		return order;
	}

	@Override
	public OrderTotalSummary calculateOrderTotal(MerchantStore store, ShopOrder order, Language language)
			throws Exception {

		Customer customer = customerFacade.getCustomerModel(order.getCustomer(), store, language);
		OrderTotalSummary summary = calculateOrderTotal(store, customer, order, language);
		this.setOrderTotals(order, summary);
		return summary;
	}

	@Override
	public OrderTotalSummary calculateOrderTotal(MerchantStore store,
			com.salesmanager.shop.model.order.v0.PersistableOrder order, Language language) throws Exception {

		List<PersistableOrderProduct> orderProducts = order.getOrderProductItems();

		ShoppingCartItemPopulator populator = new ShoppingCartItemPopulator();
		populator.setProductAttributeService(productAttributeService);
		populator.setProductService(productService);
		populator.setShoppingCartService(shoppingCartService);

		List<ShoppingCartItem> items = new ArrayList<ShoppingCartItem>();
		for (PersistableOrderProduct orderProduct : orderProducts) {
			ShoppingCartItem item = populator.populate(orderProduct, new ShoppingCartItem(), store, language);
			items.add(item);
		}

		Customer customer = customer(order.getCustomer(), store, language);

		OrderTotalSummary summary = this.calculateOrderTotal(store, customer, order, language);

		return summary;
	}

	private OrderTotalSummary calculateOrderTotal(MerchantStore store, Customer customer,
			com.salesmanager.shop.model.order.v0.PersistableOrder order, Language language) throws Exception {

		OrderTotalSummary orderTotalSummary = null;

		OrderSummary summary = new OrderSummary();

		if (order instanceof ShopOrder) {
			ShopOrder o = (ShopOrder) order;
			summary.setProducts(o.getShoppingCartItems());

			if (o.getShippingSummary() != null) {
				summary.setShippingSummary(o.getShippingSummary());
			}

			if (!StringUtils.isBlank(o.getCartCode())) {

				ShoppingCart shoppingCart = shoppingCartFacade.getShoppingCartModel(o.getCartCode(), store);

				// promo code
				if (!StringUtils.isBlank(shoppingCart.getPromoCode())) {
					Date promoDateAdded = shoppingCart.getPromoAdded();// promo
																		// valid
																		// 1 day
					Instant instant = promoDateAdded.toInstant();
					ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
					LocalDate date = zdt.toLocalDate();
					// date added < date + 1 day
					LocalDate tomorrow = LocalDate.now().plusDays(1);
					if (date.isBefore(tomorrow)) {
						summary.setPromoCode(shoppingCart.getPromoCode());
					} else {
						// clear promo
						shoppingCart.setPromoCode(null);
						shoppingCartService.saveOrUpdate(shoppingCart);
					}
				}

			}

			orderTotalSummary = orderService.caculateOrderTotal(summary, customer, store, language);
		} else {
			// need Set of ShoppingCartItem
			// PersistableOrder not implemented
			throw new Exception("calculateOrderTotal not yet implemented for PersistableOrder");
		}

		return orderTotalSummary;

	}

	private PersistableCustomer persistableCustomer(Customer customer, MerchantStore store, Language language)
			throws Exception {

		PersistableCustomerPopulator customerPopulator = new PersistableCustomerPopulator();
		PersistableCustomer persistableCustomer = customerPopulator.populate(customer, new PersistableCustomer(), store,
				language);
		return persistableCustomer;

	}

	private Customer customer(PersistableCustomer customer, MerchantStore store, Language language) throws Exception {

		Customer cust = customerPopulator.populate(customer, new Customer(), store, language);
		return cust;

	}

	private void setOrderTotals(OrderEntity order, OrderTotalSummary summary) {

		List<OrderTotal> totals = new ArrayList<OrderTotal>();
		List<com.salesmanager.core.model.order.OrderTotal> orderTotals = summary.getTotals();
		for (com.salesmanager.core.model.order.OrderTotal t : orderTotals) {
			OrderTotal total = new OrderTotal();
			total.setCode(t.getOrderTotalCode());
			total.setTitle(t.getTitle());
			total.setValue(t.getValue());
			totals.add(total);
		}

		order.setTotals(totals);

	}

	/**
	 * Submitted object must be valided prior to the invocation of this method
	 */
	@Override
	public Order processOrder(ShopOrder order, Customer customer, MerchantStore store, Language language)
			throws ServiceException {

		return processOrderModel(order, customer, null, store, language);

	}

	@Override
	public Order processOrder(ShopOrder order, Customer customer, Transaction transaction, MerchantStore store,
			Language language) throws ServiceException {

		return processOrderModel(order, customer, transaction, store, language);

	}

	/**
	 * Commit an order
	 * @param order
	 * @param customer
	 * @param transaction
	 * @param store
	 * @param language
	 * @return
	 * @throws ServiceException
	 */
	private Order processOrderModel(ShopOrder order, Customer customer, Transaction transaction, MerchantStore store,
			Language language) throws ServiceException {

		try {

			if (order.isShipToBillingAdress()) {// customer shipping is billing
				PersistableCustomer orderCustomer = order.getCustomer();
				Address billing = orderCustomer.getBilling();
				orderCustomer.setDelivery(billing);
			}

			Order modelOrder = new Order();
			modelOrder.setDatePurchased(new Date());
			modelOrder.setBilling(customer.getBilling());
			modelOrder.setDelivery(customer.getDelivery());
			modelOrder.setPaymentModuleCode(order.getPaymentModule());
			modelOrder.setPaymentType(PaymentType.valueOf(order.getPaymentMethodType()));
			modelOrder.setShippingModuleCode(order.getShippingModule());
			modelOrder.setCustomerAgreement(order.isCustomerAgreed());
			modelOrder.setLocale(LocaleUtils.getLocale(store));// set the store
																// locale based
																// on the
																// country for
																// order $
																// formatting

			List<ShoppingCartItem> shoppingCartItems = order.getShoppingCartItems();
			Set<OrderProduct> orderProducts = new LinkedHashSet<OrderProduct>();

			if (!StringUtils.isBlank(order.getComments())) {
				OrderStatusHistory statusHistory = new OrderStatusHistory();
				statusHistory.setStatus(OrderStatus.ORDERED);
				statusHistory.setOrder(modelOrder);
				statusHistory.setDateAdded(new Date());
				statusHistory.setComments(order.getComments());
				modelOrder.getOrderHistory().add(statusHistory);
			}

			OrderProductPopulator orderProductPopulator = new OrderProductPopulator();
			orderProductPopulator.setDigitalProductService(digitalProductService);
			orderProductPopulator.setProductAttributeService(productAttributeService);
			orderProductPopulator.setProductService(productService);
			String shoppingCartCode = null;

			for (ShoppingCartItem item : shoppingCartItems) {

				if(shoppingCartCode == null && item.getShoppingCart()!=null) {
					shoppingCartCode = item.getShoppingCart().getShoppingCartCode();
				}

				/**
				 * Before processing order quantity of item must be > 0
				 */

				Product product = productService.getBySku(item.getSku(), store, language);
				if (product == null) {
					throw new ServiceException(ServiceException.EXCEPTION_INVENTORY_MISMATCH);
				}

				LOGGER.debug("Validate inventory");
				for (ProductAvailability availability : product.getAvailabilities()) {
					if (availability.getRegion().equals(Constants.ALL_REGIONS)) {
						int qty = availability.getProductQuantity();
						if (qty < item.getQuantity()) {
							throw new ServiceException(ServiceException.EXCEPTION_INVENTORY_MISMATCH);
						}
					}
				}

				OrderProduct orderProduct = new OrderProduct();
				orderProduct = orderProductPopulator.populate(item, orderProduct, store, language);
				orderProduct.setOrder(modelOrder);
				orderProducts.add(orderProduct);
			}

			modelOrder.setOrderProducts(orderProducts);

			OrderTotalSummary summary = order.getOrderTotalSummary();
			List<com.salesmanager.core.model.order.OrderTotal> totals = summary.getTotals();

			// re-order totals
			Collections.sort(totals, new Comparator<com.salesmanager.core.model.order.OrderTotal>() {
				public int compare(com.salesmanager.core.model.order.OrderTotal x,
						com.salesmanager.core.model.order.OrderTotal y) {
					if (x.getSortOrder() == y.getSortOrder())
						return 0;
					return x.getSortOrder() < y.getSortOrder() ? -1 : 1;
				}

			});

			Set<com.salesmanager.core.model.order.OrderTotal> modelTotals = new LinkedHashSet<com.salesmanager.core.model.order.OrderTotal>();
			for (com.salesmanager.core.model.order.OrderTotal total : totals) {
				total.setOrder(modelOrder);
				modelTotals.add(total);
			}

			modelOrder.setOrderTotal(modelTotals);
			modelOrder.setTotal(order.getOrderTotalSummary().getTotal());

			// order misc objects
			modelOrder.setCurrency(store.getCurrency());
			modelOrder.setMerchant(store);

			// customer object
			orderCustomer(customer, modelOrder, language);

			// populate shipping information
			if (!StringUtils.isBlank(order.getShippingModule())) {
				modelOrder.setShippingModuleCode(order.getShippingModule());
			}

			String paymentType = order.getPaymentMethodType();
			Payment payment = new Payment();
			payment.setPaymentType(PaymentType.valueOf(paymentType));
			payment.setAmount(order.getOrderTotalSummary().getTotal());
			payment.setModuleName(order.getPaymentModule());
			payment.setCurrency(modelOrder.getCurrency());

			if (order.getPayment() != null && order.getPayment().get("paymentToken") != null) {// set
																				// token
				String paymentToken = order.getPayment().get("paymentToken");
				Map<String, String> paymentMetaData = new HashMap<String, String>();
				payment.setPaymentMetaData(paymentMetaData);
				paymentMetaData.put("paymentToken", paymentToken);
			}

			if (PaymentType.CREDITCARD.name().equals(paymentType)) {

				payment = new CreditCardPayment();
				((CreditCardPayment) payment).setCardOwner(order.getPayment().get("creditcard_card_holder"));
				((CreditCardPayment) payment)
						.setCredidCardValidationNumber(order.getPayment().get("creditcard_card_cvv"));
				((CreditCardPayment) payment).setCreditCardNumber(order.getPayment().get("creditcard_card_number"));
				((CreditCardPayment) payment)
						.setExpirationMonth(order.getPayment().get("creditcard_card_expirationmonth"));
				((CreditCardPayment) payment)
						.setExpirationYear(order.getPayment().get("creditcard_card_expirationyear"));

				Map<String, String> paymentMetaData = order.getPayment();
				payment.setPaymentMetaData(paymentMetaData);
				payment.setPaymentType(PaymentType.valueOf(paymentType));
				payment.setAmount(order.getOrderTotalSummary().getTotal());
				payment.setModuleName(order.getPaymentModule());
				payment.setCurrency(modelOrder.getCurrency());

				CreditCardType creditCardType = null;
				String cardType = order.getPayment().get("creditcard_card_type");

				// supported credit cards
				if (CreditCardType.AMEX.name().equalsIgnoreCase(cardType)) {
					creditCardType = CreditCardType.AMEX;
				} else if (CreditCardType.VISA.name().equalsIgnoreCase(cardType)) {
					creditCardType = CreditCardType.VISA;
				} else if (CreditCardType.MASTERCARD.name().equalsIgnoreCase(cardType)) {
					creditCardType = CreditCardType.MASTERCARD;
				} else if (CreditCardType.DINERS.name().equalsIgnoreCase(cardType)) {
					creditCardType = CreditCardType.DINERS;
				} else if (CreditCardType.DISCOVERY.name().equalsIgnoreCase(cardType)) {
					creditCardType = CreditCardType.DISCOVERY;
				}

				((CreditCardPayment) payment).setCreditCard(creditCardType);

				if (creditCardType != null) {

					CreditCard cc = new CreditCard();
					cc.setCardType(creditCardType);
					cc.setCcCvv(((CreditCardPayment) payment).getCredidCardValidationNumber());
					cc.setCcOwner(((CreditCardPayment) payment).getCardOwner());
					cc.setCcExpires(((CreditCardPayment) payment).getExpirationMonth() + "-"
							+ ((CreditCardPayment) payment).getExpirationYear());

					// hash credit card number
					if (!StringUtils.isBlank(cc.getCcNumber())) {
						String maskedNumber = CreditCardUtils
								.maskCardNumber(order.getPayment().get("creditcard_card_number"));
						cc.setCcNumber(maskedNumber);
						modelOrder.setCreditCard(cc);
					}

				}

			}

			if (PaymentType.PAYPAL.name().equals(paymentType)) {

				// check for previous transaction
				if (transaction == null) {
					throw new ServiceException("payment.error");
				}

				payment = new com.salesmanager.core.model.payments.PaypalPayment();

				((com.salesmanager.core.model.payments.PaypalPayment) payment)
						.setPayerId(transaction.getTransactionDetails().get("PAYERID"));
				((com.salesmanager.core.model.payments.PaypalPayment) payment)
						.setPaymentToken(transaction.getTransactionDetails().get("TOKEN"));

			}

			modelOrder.setShoppingCartCode(shoppingCartCode);
			modelOrder.setPaymentModuleCode(order.getPaymentModule());
			payment.setModuleName(order.getPaymentModule());

			if (transaction != null) {
				orderService.processOrder(modelOrder, customer, order.getShoppingCartItems(), summary, payment, store);
			} else {
				orderService.processOrder(modelOrder, customer, order.getShoppingCartItems(), summary, payment,
						transaction, store);
			}

			return modelOrder;

		} catch (ServiceException se) {// may be invalid credit card
			throw se;
		} catch (Exception e) {
			throw new ServiceException(e);
		}

	}

	private void orderCustomer(Customer customer, Order order, Language language) throws Exception {

		// populate customer
		order.setBilling(customer.getBilling());
		order.setDelivery(customer.getDelivery());
		order.setCustomerEmailAddress(customer.getEmailAddress());
		order.setCustomerId(customer.getId());
		//set username
		if(! customer.isAnonymous() && !StringUtils.isBlank(customer.getPassword())) {
			customer.setNick(customer.getEmailAddress());
		}

	}

	@Override
	public Customer initEmptyCustomer(MerchantStore store) {

		Customer customer = new Customer();
		Billing billing = new Billing();
		billing.setCountry(store.getCountry());
		billing.setZone(store.getZone());
		billing.setState(store.getStorestateprovince());
		/** empty postal code for initial quote **/
		// billing.setPostalCode(store.getStorepostalcode());
		customer.setBilling(billing);

		Delivery delivery = new Delivery();
		delivery.setCountry(store.getCountry());
		delivery.setZone(store.getZone());
		delivery.setState(store.getStorestateprovince());
		/** empty postal code for initial quote **/
		// delivery.setPostalCode(store.getStorepostalcode());
		customer.setDelivery(delivery);

		return customer;
	}

	@Override
	public void refreshOrder(ShopOrder order, MerchantStore store, Customer customer, ShoppingCart shoppingCart,
			Language language) throws Exception {
		if (customer == null && order.getCustomer() != null) {
			order.getCustomer().setId(0L);// reset customer id
		}

		if (customer != null) {
			PersistableCustomer persistableCustomer = persistableCustomer(customer, store, language);
			order.setCustomer(persistableCustomer);
		}

		List<ShoppingCartItem> items = new ArrayList<ShoppingCartItem>(shoppingCart.getLineItems());
		order.setShoppingCartItems(items);

		return;
	}

	@Override
	public ShippingQuote getShippingQuote(PersistableCustomer persistableCustomer, ShoppingCart cart, ShopOrder order,
			MerchantStore store, Language language) throws Exception {

		// create shipping products
		List<ShippingProduct> shippingProducts = shoppingCartService.createShippingProduct(cart);

		if (CollectionUtils.isEmpty(shippingProducts)) {
			return null;// products are virtual
		}

		Customer customer = customerFacade.getCustomerModel(persistableCustomer, store, language);

		Delivery delivery = new Delivery();

		// adjust shipping and billing
		if (order.isShipToBillingAdress() && !order.isShipToDeliveryAddress()) {

			Billing billing = customer.getBilling();

			String postalCode = billing.getPostalCode();
			postalCode = validatePostalCode(postalCode);

			delivery.setAddress(billing.getAddress());
			delivery.setCompany(billing.getCompany());
			delivery.setCity(billing.getCity());
			delivery.setPostalCode(billing.getPostalCode());
			delivery.setState(billing.getState());
			delivery.setCountry(billing.getCountry());
			delivery.setZone(billing.getZone());
		} else {
			delivery = customer.getDelivery();
		}

		ShippingQuote quote = shippingService.getShippingQuote(cart.getId(), store, delivery, shippingProducts,
				language);

		return quote;

	}

	private String validatePostalCode(String postalCode) {

		String patternString = "__";// this one is set in the template
		if (postalCode.contains(patternString)) {
			postalCode = null;
		}
		return postalCode;
	}

	@Override
	public List<Country> getShipToCountry(MerchantStore store, Language language) throws Exception {

		List<Country> shippingCountriesList = shippingService.getShipToCountryList(store, language);
		return shippingCountriesList;

	}

	/**
	 * ShippingSummary contains the subset of information of a ShippingQuote
	 */
	@Override
	public ShippingSummary getShippingSummary(ShippingQuote quote, MerchantStore store, Language language) {

		ShippingSummary summary = new ShippingSummary();
		if (quote.getSelectedShippingOption() != null) {
			summary.setShippingQuote(true);
			summary.setFreeShipping(quote.isFreeShipping());
			summary.setTaxOnShipping(quote.isApplyTaxOnShipping());
			summary.setHandling(quote.getHandlingFees());
			summary.setShipping(quote.getSelectedShippingOption().getOptionPrice());
			summary.setShippingOption(quote.getSelectedShippingOption().getOptionName());
			summary.setShippingModule(quote.getShippingModuleCode());
			summary.setShippingOptionCode(quote.getSelectedShippingOption().getOptionCode());

			if (quote.getDeliveryAddress() != null) {
				summary.setDeliveryAddress(quote.getDeliveryAddress());
			}

		}

		return summary;
	}

	@Override
	public void validateOrder(ShopOrder order, BindingResult bindingResult, Map<String, String> messagesResult,
			MerchantStore store, Locale locale) throws ServiceException {

		Validate.notNull(messagesResult, "messagesResult should not be null");

		try {

			// Language language = (Language)request.getAttribute("LANGUAGE");

			// validate order shipping and billing
			if (StringUtils.isBlank(order.getCustomer().getBilling().getFirstName())) {
				FieldError error = new FieldError("customer.billing.firstName", "customer.billing.firstName",
						messages.getMessage("NotEmpty.customer.firstName", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.firstName",
						messages.getMessage("NotEmpty.customer.firstName", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getLastName())) {
				FieldError error = new FieldError("customer.billing.lastName", "customer.billing.lastName",
						messages.getMessage("NotEmpty.customer.lastName", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.lastName",
						messages.getMessage("NotEmpty.customer.lastName", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getEmailAddress())) {
				FieldError error = new FieldError("customer.emailAddress", "customer.emailAddress",
						messages.getMessage("NotEmpty.customer.emailAddress", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.emailAddress",
						messages.getMessage("NotEmpty.customer.emailAddress", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getAddress())) {
				FieldError error = new FieldError("customer.billing.address", "customer.billing.address",
						messages.getMessage("NotEmpty.customer.billing.address", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.address",
						messages.getMessage("NotEmpty.customer.billing.address", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getCity())) {
				FieldError error = new FieldError("customer.billing.city", "customer.billing.city",
						messages.getMessage("NotEmpty.customer.billing.city", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.city",
						messages.getMessage("NotEmpty.customer.billing.city", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getCountry())) {
				FieldError error = new FieldError("customer.billing.country", "customer.billing.country",
						messages.getMessage("NotEmpty.customer.billing.country", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.country",
						messages.getMessage("NotEmpty.customer.billing.country", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getZone())
					&& StringUtils.isBlank(order.getCustomer().getBilling().getStateProvince())) {
				FieldError error = new FieldError("customer.billing.stateProvince", "customer.billing.stateProvince",
						messages.getMessage("NotEmpty.customer.billing.stateProvince", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.stateProvince",
						messages.getMessage("NotEmpty.customer.billing.stateProvince", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getPhone())) {
				FieldError error = new FieldError("customer.billing.phone", "customer.billing.phone",
						messages.getMessage("NotEmpty.customer.billing.phone", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.phone",
						messages.getMessage("NotEmpty.customer.billing.phone", locale));
			}

			if (StringUtils.isBlank(order.getCustomer().getBilling().getPostalCode())) {
				FieldError error = new FieldError("customer.billing.postalCode", "customer.billing.postalCode",
						messages.getMessage("NotEmpty.customer.billing.postalCode", locale));
				bindingResult.addError(error);
				messagesResult.put("customer.billing.postalCode",
						messages.getMessage("NotEmpty.customer.billing.postalCode", locale));
			}

			if (!order.isShipToBillingAdress()) {

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getFirstName())) {
					FieldError error = new FieldError("customer.delivery.firstName", "customer.delivery.firstName",
							messages.getMessage("NotEmpty.customer.shipping.firstName", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.firstName",
							messages.getMessage("NotEmpty.customer.shipping.firstName", locale));
				}

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getLastName())) {
					FieldError error = new FieldError("customer.delivery.lastName", "customer.delivery.lastName",
							messages.getMessage("NotEmpty.customer.shipping.lastName", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.lastName",
							messages.getMessage("NotEmpty.customer.shipping.lastName", locale));
				}

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getAddress())) {
					FieldError error = new FieldError("customer.delivery.address", "customer.delivery.address",
							messages.getMessage("NotEmpty.customer.shipping.address", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.address",
							messages.getMessage("NotEmpty.customer.shipping.address", locale));
				}

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getCity())) {
					FieldError error = new FieldError("customer.delivery.city", "customer.delivery.city",
							messages.getMessage("NotEmpty.customer.shipping.city", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.city",
							messages.getMessage("NotEmpty.customer.shipping.city", locale));
				}

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getCountry())) {
					FieldError error = new FieldError("customer.delivery.country", "customer.delivery.country",
							messages.getMessage("NotEmpty.customer.shipping.country", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.country",
							messages.getMessage("NotEmpty.customer.shipping.country", locale));
				}

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getZone())
						&& StringUtils.isBlank(order.getCustomer().getDelivery().getStateProvince())) {
					FieldError error = new FieldError("customer.delivery.stateProvince",
							"customer.delivery.stateProvince",
							messages.getMessage("NotEmpty.customer.shipping.stateProvince", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.stateProvince",
							messages.getMessage("NotEmpty.customer.shipping.stateProvince", locale));
				}

				if (StringUtils.isBlank(order.getCustomer().getDelivery().getPostalCode())) {
					FieldError error = new FieldError("customer.delivery.postalCode", "customer.delivery.postalCode",
							messages.getMessage("NotEmpty.customer.shipping.postalCode", locale));
					bindingResult.addError(error);
					messagesResult.put("customer.delivery.postalCode",
							messages.getMessage("NotEmpty.customer.shipping.postalCode", locale));
				}

			}

			if (bindingResult.hasErrors()) {
				return;

			}

			String paymentType = order.getPaymentMethodType();

			// validate payment
			if (paymentType == null) {
				ServiceException serviceException = new ServiceException(ServiceException.EXCEPTION_VALIDATION,
						"payment.required");
				throw serviceException;
			}

			// validate shipping
			if (shippingService.requiresShipping(order.getShoppingCartItems(), store)
					&& order.getSelectedShippingOption() == null) {
				ServiceException serviceException = new ServiceException(ServiceException.EXCEPTION_VALIDATION,
						"shipping.required");
				throw serviceException;
			}

			// pre-validate credit card
			if (PaymentType.CREDITCARD.name().equals(paymentType)
					&& "true".equals(coreConfiguration.getProperty("VALIDATE_CREDIT_CARD"))) {
				String cco = order.getPayment().get("creditcard_card_holder");
				String cvv = order.getPayment().get("creditcard_card_cvv");
				String ccn = order.getPayment().get("creditcard_card_number");
				String ccm = order.getPayment().get("creditcard_card_expirationmonth");
				String ccd = order.getPayment().get("creditcard_card_expirationyear");

				if (StringUtils.isBlank(cco) || StringUtils.isBlank(cvv) || StringUtils.isBlank(ccn)
						|| StringUtils.isBlank(ccm) || StringUtils.isBlank(ccd)) {
					ObjectError error = new ObjectError("creditcard",
							messages.getMessage("messages.error.creditcard", locale));
					bindingResult.addError(error);
					messagesResult.put("creditcard", messages.getMessage("messages.error.creditcard", locale));
					return;
				}

				CreditCardType creditCardType = null;
				String cardType = order.getPayment().get("creditcard_card_type");

				if (cardType.equalsIgnoreCase(CreditCardType.AMEX.name())) {
					creditCardType = CreditCardType.AMEX;
				} else if (cardType.equalsIgnoreCase(CreditCardType.VISA.name())) {
					creditCardType = CreditCardType.VISA;
				} else if (cardType.equalsIgnoreCase(CreditCardType.MASTERCARD.name())) {
					creditCardType = CreditCardType.MASTERCARD;
				} else if (cardType.equalsIgnoreCase(CreditCardType.DINERS.name())) {
					creditCardType = CreditCardType.DINERS;
				} else if (cardType.equalsIgnoreCase(CreditCardType.DISCOVERY.name())) {
					creditCardType = CreditCardType.DISCOVERY;
				}

				if (creditCardType == null) {
					ServiceException serviceException = new ServiceException(ServiceException.EXCEPTION_VALIDATION,
							"cc.type");
					throw serviceException;
				}

			}

		} catch (ServiceException se) {
			LOGGER.error("Error while commiting order", se);
			throw se;
		}

	}

	@Override
	public com.salesmanager.shop.model.order.v0.ReadableOrderList getReadableOrderList(MerchantStore store,
			Customer customer, int start, int maxCount, Language language) throws Exception {

		OrderCriteria criteria = new OrderCriteria();
		criteria.setStartIndex(start);
		criteria.setMaxCount(maxCount);
		criteria.setCustomerId(customer.getId());

		return this.getReadableOrderList(criteria, store, language);

	}

	@Override
	public com.salesmanager.shop.model.order.v0.ReadableOrderList getReadableOrderList(OrderCriteria criteria,
			MerchantStore store) {

		try {
			criteria.setLegacyPagination(false);

			OrderList orderList = orderService.getOrders(criteria, store);

			List<Order> orders = orderList.getOrders();
			com.salesmanager.shop.model.order.v0.ReadableOrderList returnList = new com.salesmanager.shop.model.order.v0.ReadableOrderList();

			if (CollectionUtils.isEmpty(orders)) {
				returnList.setRecordsTotal(0);
				return returnList;
			}

			List<com.salesmanager.shop.model.order.v0.ReadableOrder> readableOrders = new ArrayList<com.salesmanager.shop.model.order.v0.ReadableOrder>();
			for (Order order : orders) {
				com.salesmanager.shop.model.order.v0.ReadableOrder readableOrder = new com.salesmanager.shop.model.order.v0.ReadableOrder();
				readableOrderPopulator.populate(order, readableOrder, null, null);
				readableOrders.add(readableOrder);

			}
			returnList.setOrders(readableOrders);

			returnList.setRecordsTotal(orderList.getTotalCount());
			returnList.setTotalPages(orderList.getTotalPages());
			returnList.setNumber(orderList.getOrders().size());
			returnList.setRecordsFiltered(orderList.getOrders().size());

			return returnList;

		} catch (Exception e) {
			throw new ServiceRuntimeException("Error while getting orders", e);
		}

	}

	@Override
	public ShippingQuote getShippingQuote(Customer customer, ShoppingCart cart,
			com.salesmanager.shop.model.order.v0.PersistableOrder order, MerchantStore store, Language language)
			throws Exception {
		// create shipping products
		List<ShippingProduct> shippingProducts = shoppingCartService.createShippingProduct(cart);

		if (CollectionUtils.isEmpty(shippingProducts)) {
			return null;// products are virtual
		}

		Delivery delivery = new Delivery();

		// adjust shipping and billing
		if (order.isShipToBillingAdress()) {
			Billing billing = customer.getBilling();
			delivery.setAddress(billing.getAddress());
			delivery.setCity(billing.getCity());
			delivery.setCompany(billing.getCompany());
			delivery.setPostalCode(billing.getPostalCode());
			delivery.setState(billing.getState());
			delivery.setCountry(billing.getCountry());
			delivery.setZone(billing.getZone());
		} else {
			delivery = customer.getDelivery();
		}

		ShippingQuote quote = shippingService.getShippingQuote(cart.getId(), store, delivery, shippingProducts,
				language);

		return quote;
	}

	private com.salesmanager.shop.model.order.v0.ReadableOrderList populateOrderList(final OrderList orderList,
			final MerchantStore store, final Language language) {
		List<Order> orders = orderList.getOrders();
		com.salesmanager.shop.model.order.v0.ReadableOrderList returnList = new com.salesmanager.shop.model.order.v0.ReadableOrderList();
		if (CollectionUtils.isEmpty(orders)) {
			LOGGER.info("Order list if empty..Returning empty list");
			returnList.setRecordsTotal(0);
			// returnList.setMessage("No results for store code " + store);
			return returnList;
		}

		// ReadableOrderPopulator orderPopulator = new ReadableOrderPopulator();
		Locale locale = LocaleUtils.getLocale(language);
		readableOrderPopulator.setLocale(locale);

		List<com.salesmanager.shop.model.order.v0.ReadableOrder> readableOrders = new ArrayList<com.salesmanager.shop.model.order.v0.ReadableOrder>();
		for (Order order : orders) {
			com.salesmanager.shop.model.order.v0.ReadableOrder readableOrder = new com.salesmanager.shop.model.order.v0.ReadableOrder();
			try {
				readableOrderPopulator.populate(order, readableOrder, store, language);
				setOrderProductList(order, locale, store, language, readableOrder);
			} catch (ConversionException ex) {
				LOGGER.error("Error while converting order to order data", ex);

			}
			readableOrders.add(readableOrder);

		}

		returnList.setOrders(readableOrders);
		return returnList;

	}

	private void setOrderProductList(final Order order, final Locale locale, final MerchantStore store,
			final Language language, final com.salesmanager.shop.model.order.v0.ReadableOrder readableOrder)
			throws ConversionException {
		List<ReadableOrderProduct> orderProducts = new ArrayList<ReadableOrderProduct>();
		for (OrderProduct p : order.getOrderProducts()) {
			ReadableOrderProductPopulator orderProductPopulator = new ReadableOrderProductPopulator();
			orderProductPopulator.setLocale(locale);
			orderProductPopulator.setProductService(productService);
			orderProductPopulator.setPricingService(pricingService);
			orderProductPopulator.setimageUtils(imageUtils);
			ReadableOrderProduct orderProduct = new ReadableOrderProduct();
			orderProductPopulator.populate(p, orderProduct, store, language);

			// image

			// attributes

			orderProducts.add(orderProduct);
		}

		readableOrder.setProducts(orderProducts);
	}

	private com.salesmanager.shop.model.order.v0.ReadableOrderList getReadableOrderList(OrderCriteria criteria,
			MerchantStore store, Language language) throws Exception {

		OrderList orderList = orderService.listByStore(store, criteria);

		// ReadableOrderPopulator orderPopulator = new ReadableOrderPopulator();
		Locale locale = LocaleUtils.getLocale(language);
		readableOrderPopulator.setLocale(locale);

		List<Order> orders = orderList.getOrders();
		com.salesmanager.shop.model.order.v0.ReadableOrderList returnList = new com.salesmanager.shop.model.order.v0.ReadableOrderList();

		if (CollectionUtils.isEmpty(orders)) {
			returnList.setRecordsTotal(0);
			// returnList.setMessage("No results for store code " + store);
			return null;
		}

		List<com.salesmanager.shop.model.order.v0.ReadableOrder> readableOrders = new ArrayList<com.salesmanager.shop.model.order.v0.ReadableOrder>();
		for (Order order : orders) {
			com.salesmanager.shop.model.order.v0.ReadableOrder readableOrder = new com.salesmanager.shop.model.order.v0.ReadableOrder();
			readableOrderPopulator.populate(order, readableOrder, store, language);
			readableOrders.add(readableOrder);

		}

		returnList.setRecordsTotal(orderList.getTotalCount());
		return this.populateOrderList(orderList, store, language);

	}

	@Override
	public com.salesmanager.shop.model.order.v0.ReadableOrderList getReadableOrderList(MerchantStore store, int start,
			int maxCount, Language language) throws Exception {

		OrderCriteria criteria = new OrderCriteria();
		criteria.setStartIndex(start);
		criteria.setMaxCount(maxCount);

		return getReadableOrderList(criteria, store, language);
	}

	@Override
	public com.salesmanager.shop.model.order.v0.ReadableOrder getReadableOrder(Long orderId, MerchantStore store,
			Language language) {
		Validate.notNull(store, "MerchantStore cannot be null");
		Order modelOrder = orderService.getOrder(orderId, store);
		if (modelOrder == null) {
			throw new ResourceNotFoundException("Order not found with id " + orderId);
		}

		com.salesmanager.shop.model.order.v0.ReadableOrder readableOrder = new com.salesmanager.shop.model.order.v0.ReadableOrder();

		Long customerId = modelOrder.getCustomerId();
		if (customerId != null) {
			ReadableCustomer readableCustomer = customerFacade.getCustomerById(customerId, store, language);
			if (readableCustomer == null) {
				LOGGER.warn("Customer id " + customerId + " not found in order " + orderId);
			} else {
				readableOrder.setCustomer(readableCustomer);
			}
		}

		try {
			readableOrderPopulator.populate(modelOrder, readableOrder, store, language);

			// order products
			List<ReadableOrderProduct> orderProducts = new ArrayList<ReadableOrderProduct>();
			for (OrderProduct p : modelOrder.getOrderProducts()) {
				ReadableOrderProductPopulator orderProductPopulator = new ReadableOrderProductPopulator();
				orderProductPopulator.setProductService(productService);
				orderProductPopulator.setPricingService(pricingService);
				orderProductPopulator.setimageUtils(imageUtils);

				ReadableOrderProduct orderProduct = new ReadableOrderProduct();
				orderProductPopulator.populate(p, orderProduct, store, language);
				orderProducts.add(orderProduct);
			}

			readableOrder.setProducts(orderProducts);
		} catch (Exception e) {
			throw new ServiceRuntimeException("Error while getting order [" + orderId + "]");
		}

		return readableOrder;
	}

	@Override
	public ShippingQuote getShippingQuote(Customer customer, ShoppingCart cart, MerchantStore store, Language language)
			throws Exception {

		Validate.notNull(customer, "Customer cannot be null");
		Validate.notNull(cart, "cart cannot be null");

		// create shipping products
		List<ShippingProduct> shippingProducts = shoppingCartService.createShippingProduct(cart);

		if (CollectionUtils.isEmpty(shippingProducts)) {
			return null;// products are virtual
		}

		Delivery delivery = new Delivery();
		Billing billing = new Billing();
		//default value
		billing.setCountry(store.getCountry());


		// adjust shipping and billing
		if (customer.getDelivery() == null || StringUtils.isBlank(customer.getDelivery().getPostalCode())) {
			if(customer.getBilling()!=null) {
				billing = customer.getBilling();
			}
			delivery.setAddress(billing.getAddress());
			delivery.setCity(billing.getCity());
			delivery.setCompany(billing.getCompany());
			delivery.setPostalCode(billing.getPostalCode());
			delivery.setState(billing.getState());
			delivery.setCountry(billing.getCountry());
			delivery.setZone(billing.getZone());
		} else {
			delivery = customer.getDelivery();
		}

		ShippingQuote quote = shippingService.getShippingQuote(cart.getId(), store, delivery, shippingProducts,
				language);
		return quote;
	}

	@Override
	public Order processOrder(com.salesmanager.shop.model.order.v1.PersistableOrder order, Customer customer,
			MerchantStore store, Language language, Locale locale, String cartCode, String idempotencyKey)
			throws ServiceException {

		if (StringUtils.isBlank(idempotencyKey)) {
			return processOrder(order, customer, store, language, locale);
		}
		Validate.notNull(store, "MerchantStore cannot be null");
		if (idempotencyKey.length() > 255) {
			throw new IdempotencyConflictException("Idempotency-Key must be at most 255 characters");
		}

		String requestHash = requestHash(order);
		Integer merchantId = store.getId();

		OrderIdempotencyKey existing = orderIdempotencyService.findKey(merchantId, idempotencyKey);
		OrderIdempotencyKey claimed = null;
		if (existing == null) {
			try {
				claimed = orderIdempotencyService.claimKey(merchantId, idempotencyKey, cartCode, requestHash);
			} catch (RuntimeException e) {
				// lost the race on the unique constraint: another request owns the key
				existing = orderIdempotencyService.findKey(merchantId, idempotencyKey);
				if (existing == null) {
					throw e;
				}
			}
		}
		if (existing != null) {
			return replay(existing, cartCode, requestHash, store);
		}

		Order result;
		try {
			result = processOrder(order, customer, store, language, locale);
		} catch (OrderChargedNotRecordedException e) {
			// charged: bind the key to the pending order so a retry can never charge again
			orderIdempotencyService.completeKey(claimed.getId(), e.getOrderId());
			throw e;
		} catch (ServiceException | RuntimeException e) {
			// no charge was captured: free the key so the client can retry with it
			orderIdempotencyService.releaseKey(claimed.getId());
			throw e;
		}
		orderIdempotencyService.completeKey(claimed.getId(), result.getId());
		return result;
	}

	private Order replay(OrderIdempotencyKey existing, String cartCode, String requestHash, MerchantStore store) {
		if (!StringUtils.equals(existing.getCartCode(), cartCode)) {
			throw new IdempotencyConflictException("Idempotency-Key was already used for a different cart");
		}
		if (!StringUtils.equals(existing.getRequestHash(), requestHash)) {
			throw new IdempotencyConflictException("Idempotency-Key was already used with a different request body");
		}
		if (existing.getOrderId() == null) {
			throw new IdempotencyConflictException("A checkout with this Idempotency-Key is still in progress");
		}
		Order original = orderService.getOrder(existing.getOrderId(), store);
		if (original == null) {
			throw new IdempotencyConflictException("Order for this Idempotency-Key no longer exists");
		}
		return original;
	}

	static String requestHash(Object body) {
		try {
			byte[] json = new com.fasterxml.jackson.databind.ObjectMapper()
					.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
					.configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
					.writeValueAsBytes(body);
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(json);
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException("Cannot hash checkout request", e);
		}
	}

	/**
	 * Process order from api
	 */
	@Override
	public Order processOrder(com.salesmanager.shop.model.order.v1.PersistableOrder order, Customer customer,
			MerchantStore store, Language language, Locale locale) throws ServiceException {

		Validate.notNull(order, "Order cannot be null");
		Validate.notNull(customer, "Customer cannot be null");
		Validate.notNull(store, "MerchantStore cannot be null");
		Validate.notNull(language, "Language cannot be null");
		Validate.notNull(locale, "Locale cannot be null");

		try {


			Order modelOrder = new Order();
			persistableOrderApiPopulator.populate(order, modelOrder, store, language);

			Long shoppingCartId = order.getShoppingCartId();
			ShoppingCart cart = shoppingCartService.getById(shoppingCartId, store);

			if (cart == null) {
				throw new ServiceException("Shopping cart with id " + shoppingCartId + " does not exist");
			}

			Set<ShoppingCartItem> shoppingCartItems = cart.getLineItems();

			List<ShoppingCartItem> items = new ArrayList<ShoppingCartItem>(shoppingCartItems);

			Set<OrderProduct> orderProducts = new LinkedHashSet<OrderProduct>();

			OrderProductPopulator orderProductPopulator = new OrderProductPopulator();
			orderProductPopulator.setDigitalProductService(digitalProductService);
			orderProductPopulator.setProductAttributeService(productAttributeService);
			orderProductPopulator.setProductService(productService);

			for (ShoppingCartItem item : shoppingCartItems) {
				OrderProduct orderProduct = new OrderProduct();
				orderProduct = orderProductPopulator.populate(item, orderProduct, store, language);
				orderProduct.setOrder(modelOrder);
				orderProducts.add(orderProduct);
			}

			modelOrder.setOrderProducts(orderProducts);

			if (order.getAttributes() != null && order.getAttributes().size() > 0) {
				Set<OrderAttribute> attrs = new HashSet<OrderAttribute>();
				for (com.salesmanager.shop.model.order.OrderAttribute attribute : order.getAttributes()) {
					OrderAttribute attr = new OrderAttribute();
					attr.setKey(attribute.getKey());
					attr.setValue(attribute.getValue());
					attr.setOrder(modelOrder);
					attrs.add(attr);
				}
				modelOrder.setOrderAttributes(attrs);
			}

			// requires Shipping information (need a quote id calculated)
			ShippingSummary shippingSummary = null;

			// get shipping quote if asked for
			if (order.getShippingQuote() != null && order.getShippingQuote().longValue() > 0) {
				shippingSummary = shippingQuoteService.getShippingSummary(order.getShippingQuote(), store);
				if (shippingSummary != null) {
					modelOrder.setShippingModuleCode(shippingSummary.getShippingModule());
				}
			}

			// requires Order Totals, this needs recalculation and then compare
			// total with the amount sent as part
			// of process order request. If totals does not match, an error
			// should be thrown.

			OrderTotalSummary orderTotalSummary = null;

			OrderSummary orderSummary = new OrderSummary();
			orderSummary.setShippingSummary(shippingSummary);
			List<ShoppingCartItem> itemsSet = new ArrayList<ShoppingCartItem>(cart.getLineItems());
			orderSummary.setProducts(itemsSet);

			orderTotalSummary = orderService.caculateOrderTotal(orderSummary, customer, store, language);

			if (order.getPayment().getAmount() == null) {
				throw new ConversionException("Requires Payment.amount");
			}

			String submitedAmount = order.getPayment().getAmount();

			BigDecimal formattedSubmittedAmount = productPriceUtils.getAmount(submitedAmount);

			BigDecimal submitedAmountFormat = productPriceUtils.getAmount(submitedAmount);

			BigDecimal calculatedAmount = orderTotalSummary.getTotal();
			String strCalculatedTotal = calculatedAmount.toPlainString();

			// compare both prices
			if (calculatedAmount.compareTo(formattedSubmittedAmount) != 0) {


				throw new ConversionException("Payment.amount does not match what the system has calculated "
						+ strCalculatedTotal + " (received " + submitedAmount + ") please recalculate the order and submit again");
			}

			modelOrder.setTotal(calculatedAmount);
			List<com.salesmanager.core.model.order.OrderTotal> totals = orderTotalSummary.getTotals();
			Set<com.salesmanager.core.model.order.OrderTotal> set = new HashSet<com.salesmanager.core.model.order.OrderTotal>();

			if (!CollectionUtils.isEmpty(totals)) {
				for (com.salesmanager.core.model.order.OrderTotal total : totals) {
					total.setOrder(modelOrder);
					set.add(total);
				}
			}
			modelOrder.setOrderTotal(set);

			PersistablePaymentPopulator paymentPopulator = new PersistablePaymentPopulator();
			paymentPopulator.setPricingService(pricingService);
			Payment paymentModel = new Payment();
			paymentPopulator.populate(order.getPayment(), paymentModel, store, language);

			modelOrder.setShoppingCartCode(cart.getShoppingCartCode());

			//lookup existing customer
			//if customer exist then do not set authentication for this customer and send an instructions email
			/** **/
			if(!StringUtils.isBlank(customer.getNick()) && !customer.isAnonymous()) {
				if(order.getCustomerId() == null && (customerFacade.checkIfUserExists(customer.getNick(), store))) {
					customer.setAnonymous(true);
					customer.setNick(null);
					//send email instructions
				}
			}


			//order service
			modelOrder = orderService.processOrder(modelOrder, customer, items, orderTotalSummary, paymentModel, store);

			// update cart
			try {
				cart.setOrderId(modelOrder.getId());
				shoppingCartFacade.saveOrUpdateShoppingCart(cart);
			} catch (Exception e) {
				LOGGER.error("Cannot delete cart " + cart.getId(), e);
			}

			//email management
			if ("true".equals(coreConfiguration.getProperty("ORDER_EMAIL_API"))) {
				// send email
				try {

					notify(modelOrder, customer, store, language, locale);


				} catch (Exception e) {
					LOGGER.error("Cannot send order confirmation email", e);
				}
			}

			return modelOrder;

		} catch (OrderChargedNotRecordedException e) {
			// keep the type: the idempotency key is bound to this order and the API maps
			// PaymentConfirmationPendingException to 202
			throw e;
		} catch (Exception e) {

			throw new ServiceException(e);

		}

	}

	@Async
	private void notify(Order order, Customer customer, MerchantStore store, Language language, Locale locale) throws Exception {

		// send order confirmation email to customer
		emailTemplatesUtils.sendOrderEmail(customer.getEmailAddress(), customer, order, locale,
				language, store, coreConfiguration.getProperty("CONTEXT_PATH"));

		if (orderService.hasDownloadFiles(order)) {
			emailTemplatesUtils.sendOrderDownloadEmail(customer, order, store, locale,
					coreConfiguration.getProperty("CONTEXT_PATH"));
		}

		// send customer credentials

		// send order confirmation email to merchant
		emailTemplatesUtils.sendOrderEmail(store.getStoreEmailAddress(), customer, order, locale,
				language, store, coreConfiguration.getProperty("CONTEXT_PATH"));


	}

	@Override
	public com.salesmanager.shop.model.order.v0.ReadableOrderList getCapturableOrderList(MerchantStore store,
			Date startDate, Date endDate, Language language) throws Exception {

		// get all transactions for the given date
		List<Order> orders = orderService.getCapturableOrders(store, startDate, endDate);

		// ReadableOrderPopulator orderPopulator = new ReadableOrderPopulator();
		Locale locale = LocaleUtils.getLocale(language);
		readableOrderPopulator.setLocale(locale);

		com.salesmanager.shop.model.order.v0.ReadableOrderList returnList = new com.salesmanager.shop.model.order.v0.ReadableOrderList();

		if (CollectionUtils.isEmpty(orders)) {
			returnList.setRecordsTotal(0);
			// returnList.setMessage("No results for store code " + store);
			return returnList;
		}

		List<com.salesmanager.shop.model.order.v0.ReadableOrder> readableOrders = new ArrayList<com.salesmanager.shop.model.order.v0.ReadableOrder>();
		for (Order order : orders) {
			com.salesmanager.shop.model.order.v0.ReadableOrder readableOrder = new com.salesmanager.shop.model.order.v0.ReadableOrder();
			readableOrderPopulator.populate(order, readableOrder, store, language);
			readableOrders.add(readableOrder);

		}

		returnList.setRecordsTotal(orders.size());
		returnList.setOrders(readableOrders);

		return returnList;
	}

	@Override
	public ReadableTransaction captureOrder(MerchantStore store, Order order, Customer customer, Language language)
			throws Exception {
		Transaction transactionModel = paymentService.processCapturePayment(order, customer, store);

		ReadableTransaction transaction = new ReadableTransaction();
		ReadableTransactionPopulator trxPopulator = new ReadableTransactionPopulator();
		trxPopulator.setOrderService(orderService);
		trxPopulator.setPricingService(pricingService);

		trxPopulator.populate(transactionModel, transaction, store, language);

		return transaction;

	}

	@Override
	public ReadableTransaction captureOrder(MerchantStore store, Long orderId, Language language) {
		Validate.notNull(store, "MerchantStore must not be null");
		Order order = storeOrder(orderId, store);
		try {
			if (transactionService.getCapturableTransaction(order) == null) {
				throw new ConflictException("Order id [" + orderId + "] has no capturable transaction");
			}
			Customer customer = order.getCustomerId() == null ? null : customerService.getById(order.getCustomerId());
			if (customer == null) {
				throw new ResourceNotFoundException("Order id [" + orderId + "] has an invalid customer [" + order.getCustomerId() + "]");
			}
			return toReadable(paymentService.processCapturePayment(order, customer, store), store, language);
		} catch (ServiceException e) {
			throw paymentFailure("capture", orderId, e);
		} catch (IllegalOrderStatusTransitionException e) {
			throw new ConflictException(e.getMessage());
		}
	}

	@Override
	public ReadableTransaction refundOrder(MerchantStore store, Long orderId, BigDecimal amount, Language language) {
		Validate.notNull(store, "MerchantStore must not be null");
		Order order = storeOrder(orderId, store);
		try {
			if (transactionService.getRefundableTransaction(order) == null) {
				throw new ConflictException("Order id [" + orderId + "] has no captured payment to refund");
			}
			BigDecimal remaining = transactionService.getRefundableAmount(order);
			if (remaining.signum() <= 0) {
				throw new ConflictException("Order id [" + orderId + "] is already fully refunded");
			}
			BigDecimal refundAmount = amount == null ? remaining : amount;
			if (refundAmount.signum() <= 0 || refundAmount.compareTo(remaining) > 0) {
				throw new RestApiException("400",
						"Refund amount must be greater than 0 and at most " + remaining + ", was " + refundAmount);
			}
			Transaction refund = paymentService.processRefund(order, null, store, refundAmount);
			boolean full = refundAmount.compareTo(remaining) == 0;
			if (full) {
				restockAfterRefund(orderId);
			}
			notifyStatus(order, store, (full ? "Refund of " : "Partial refund of ") + amount(refundAmount, order));
			return toReadable(refund, store, language);
		} catch (ServiceException e) {
			throw paymentFailure("refund", orderId, e);
		} catch (IllegalOrderStatusTransitionException e) {
			throw new ConflictException(e.getMessage());
		}
	}

	/**
	 * The refund is already committed at the gateway and in the ledger, so a
	 * failure to return the stock must not turn the response into an error.
	 */
	private void restockAfterRefund(Long orderId) {
		try {
			orderCheckoutSteps.restock(orderId);
		} catch (RuntimeException e) {
			LOGGER.error("Order " + orderId + " fully refunded but its stock was not returned", e);
		}
	}

	private static String amount(BigDecimal value, Order order) {
		return value.toPlainString() + (order.getCurrency() == null ? "" : " " + order.getCurrency().getCode());
	}

	/**
	 * Sends the order status email (email_template_order_status) for a change that
	 * is already committed. Gated like the checkout confirmation by ORDER_EMAIL_API;
	 * the send itself is asynchronous and reloads the order.
	 */
	private void notifyStatus(Order order, MerchantStore store, String comment) {
		if (!emailsEnabled()) {
			return;
		}
		try {
			OrderStatusHistory change = new OrderStatusHistory();
			change.setDateAdded(new Date());
			change.setComments(comment);
			Customer customer = null;
			if (order.getCustomerId() != null) {
				customer = new Customer();
				customer.setId(order.getCustomerId());
			}
			emailTemplatesUtils.sendUpdateOrderStatusEmail(customer, order, change, store, LocaleUtils.getLocale(store),
					coreConfiguration.getProperty("CONTEXT_PATH"));
		} catch (RuntimeException e) {
			LOGGER.error("Order " + order.getId() + " status email not sent", e);
		}
	}

	private boolean emailsEnabled() {
		return "true".equals(coreConfiguration.getProperty("ORDER_EMAIL_API"));
	}

	@Override
	public ReadableOrderLifecycle getOrderLifecycle(Long orderId, MerchantStore store) {
		Validate.notNull(store, "MerchantStore must not be null");
		return lifecycle(storeOrder(orderId, store), null);
	}

	@Override
	public ReadableOrderLifecycle cancelOrder(Long orderId, String comment, MerchantStore store) {
		Validate.notNull(store, "MerchantStore must not be null");
		Order order = storeOrder(orderId, store);
		String done;
		try {
			done = orderCommandService.cancel(order, store, comment);
		} catch (OrderCommandRefusedException | IllegalOrderStatusTransitionException e) {
			throw new ConflictException(e.getMessage());
		} catch (ServiceException e) {
			throw paymentFailure("cancel", orderId, e);
		}
		Order canceled = storeOrder(orderId, store);
		notifyStatus(canceled, store, done);
		return lifecycle(canceled, done);
	}

	@Override
	public ReadableOrderLifecycle recheckPayment(Long orderId, MerchantStore store) {
		Validate.notNull(store, "MerchantStore must not be null");
		Order order = storeOrder(orderId, store);
		if (order.getStatus() != OrderStatus.PAYMENT_PENDING && order.getStatus() != OrderStatus.PAYMENT_REVIEW) {
			throw new ConflictException("Order id [" + orderId + "] is " + order.getStatus()
					+ "; only PAYMENT_PENDING and PAYMENT_REVIEW orders can be re-checked");
		}
		PendingOrderReconciler.Result result = pendingOrderReconciler.recheckOrder(orderId);
		if (result.errors > 0 || result.lookupErrors > 0) {
			throw new GenericRuntimeException("502",
					"Could not get an answer from the payment gateway for order [" + orderId + "]; try again later");
		}
		Order after = storeOrder(orderId, store);
		String message;
		if (result.finalised > 0) {
			message = "Payment confirmed; order is now " + after.getStatus();
		} else if (result.failed > 0) {
			message = "No payment was taken; order marked " + after.getStatus() + " and its stock returned";
		} else {
			message = "The gateway has not confirmed the payment yet; order stays " + after.getStatus();
		}
		return lifecycle(after, message);
	}

	private ReadableOrderLifecycle lifecycle(Order order, String message) {
		ReadableOrderLifecycle readable = new ReadableOrderLifecycle();
		OrderStatus status = order.getStatus();
		readable.setOrderId(order.getId());
		readable.setStatus(status == null ? null : status.name());
		readable.setNext(names(OrderStatusTransitions.allowedFrom(status)));
		readable.setStatusUpdates(names(OrderCommandService.statusUpdatesFrom(status)));
		readable.setCancelable(OrderStatusTransitions.allowedFrom(status).contains(OrderStatus.CANCELED));
		readable.setPaymentRecheck(status == OrderStatus.PAYMENT_PENDING || status == OrderStatus.PAYMENT_REVIEW);
		try {
			readable.setRefundable(transactionService.getRefundableAmount(order));
		} catch (ServiceException e) {
			throw new ServiceRuntimeException("Error while reading the payments of order [" + order.getId() + "]", e);
		}
		readable.setCurrency(order.getCurrency() == null ? null : order.getCurrency().getCode());
		readable.setMessage(message);
		return readable;
	}

	/** Names in the enum's declaration order, so clients get a stable list. */
	private static List<String> names(Set<OrderStatus> statuses) {
		List<String> names = new ArrayList<>();
		for (OrderStatus s : OrderStatus.values()) {
			if (statuses.contains(s)) {
				names.add(s.name());
			}
		}
		return names;
	}

	private Order storeOrder(Long orderId, MerchantStore store) {
		Order order = orderId == null ? null : orderService.getOrder(orderId, store);
		if (order == null) {
			throw new ResourceNotFoundException("Order id [" + orderId + "] not found for store [" + store.getCode() + "]");
		}
		return order;
	}

	private ReadableTransaction toReadable(Transaction transactionModel, MerchantStore store, Language language)
			throws ServiceException {
		ReadableTransaction transaction = new ReadableTransaction();
		ReadableTransactionPopulator trxPopulator = new ReadableTransactionPopulator();
		trxPopulator.setOrderService(orderService);
		trxPopulator.setPricingService(pricingService);
		try {
			trxPopulator.populate(transactionModel, transaction, store, language);
		} catch (ConversionException e) {
			throw new ServiceException(e);
		}
		return transaction;
	}

	/** Validation failures from the payment service are client errors; anything else (gateway) is a server error. */
	private RuntimeException paymentFailure(String operation, Long orderId, ServiceException e) {
		if (e.getExceptionType() == ServiceException.EXCEPTION_VALIDATION) {
			return new RestApiException("400", e.getMessage());
		}
		LOGGER.error("Payment " + operation + " failed for order [" + orderId + "]", e);
		return new GenericRuntimeException("500", "Payment " + operation + " failed for order [" + orderId + "]: " + e.getMessage(), e);
	}

	@Override
	public List<ReadableOrderStatusHistory> getReadableOrderHistory(Long orderId, MerchantStore store,
			Language language) {

		Order order = orderService.getOrder(orderId, store);
		if (order == null) {
			throw new ResourceNotFoundException(
					"Order id [" + orderId + "] not found for merchand [" + store.getId() + "]");
		}

		Set<OrderStatusHistory> historyList = order.getOrderHistory();
		List<ReadableOrderStatusHistory> returnList = historyList.stream().map(f -> mapToReadbleOrderStatusHistory(f))
				.collect(Collectors.toList());
		return returnList;
	}

	ReadableOrderStatusHistory mapToReadbleOrderStatusHistory(OrderStatusHistory source) {
		ReadableOrderStatusHistory readable = new ReadableOrderStatusHistory();
		readable.setComments(source.getComments());
		readable.setDate(DateUtil.formatLongDate(source.getDateAdded()));
		readable.setId(source.getId());
		readable.setOrderId(source.getOrder().getId());
		readable.setOrderStatus(source.getStatus().name());

		return readable;
	}

	@Override
	public void createOrderStatus(PersistableOrderStatusHistory status, Long id, MerchantStore store) {
		Validate.notNull(status, "OrderStatusHistory must not be null");
		Validate.notNull(id, "Order id must not be null");
		Validate.notNull(store, "MerchantStore must not be null");

		// retrieve original order
		Order order = orderService.getOrder(id, store);
		if (order == null) {
			throw new ResourceNotFoundException(
					"Order with id [" + id + "] does not exist for merchant [" + store.getCode() + "]");
		}

		// The history records what happened to the order; it does not change it. A
		// different status here would contradict Order.status, so changes go through
		// PUT /status (fulfilment) or the cancel and refund endpoints.
		if (status.getStatus() != null && status.getStatus() != order.getStatus()) {
			throw new ConflictException("Order id [" + id + "] is " + order.getStatus() + "; a history note cannot set "
					+ status.getStatus() + ". Change the status with PUT /private/orders/" + id
					+ "/status, or use the cancel and refund endpoints");
		}

		try {
			OrderStatusHistory history = new OrderStatusHistory();
			history.setComments(status.getComments());
			history.setDateAdded(StringUtils.isBlank(status.getDate()) ? new Date() : DateUtil.getDate(status.getDate()));
			history.setOrder(order);
			history.setStatus(order.getStatus());

			orderService.addOrderStatusHistory(order, history);

		} catch (Exception e) {
			throw new ServiceRuntimeException("An error occured while converting orderstatushistory", e);
		}

	}

	@Override
	public void updateOrderCustomre(Long orderId, PersistableCustomer customer, MerchantStore store) {
		// TODO Auto-generated method stub

		try {

		//get order by order id
		Order modelOrder = orderService.getOrder(orderId, store);

		if(modelOrder == null) {
			throw new ResourceNotFoundException("Order id [" + orderId + "] not found for store [" + store.getCode() + "]");
		}

		//set customer information
		modelOrder.setCustomerEmailAddress(customer.getEmailAddress());
		modelOrder.setBilling(this.convertBilling(customer.getBilling()));
		modelOrder.setDelivery(this.convertDelivery(customer.getDelivery()));

		orderService.saveOrUpdate(modelOrder);

		} catch(Exception e) {
			throw new ServiceRuntimeException("An error occured while updating order customer", e);
		}

	}

	private Billing convertBilling(Address source) throws ServiceException {
		Billing target = new Billing();
        target.setCity(source.getCity());
        target.setCompany(source.getCompany());
        target.setFirstName(source.getFirstName());
        target.setLastName(source.getLastName());
        target.setPostalCode(source.getPostalCode());
        target.setTelephone(source.getPhone());
        target.setAddress(source.getAddress());
        if(source.getCountry()!=null) {
        	target.setCountry(countryService.getByCode(source.getCountry()));
        }

        if(source.getZone()!=null) {
            target.setZone(zoneService.getByCode(source.getZone()));
        }
        target.setState(source.getBilstateOther());

        return target;
	}

	private Delivery convertDelivery(Address source) throws ServiceException {
		Delivery target = new Delivery();
        target.setCity(source.getCity());
        target.setCompany(source.getCompany());
        target.setFirstName(source.getFirstName());
        target.setLastName(source.getLastName());
        target.setPostalCode(source.getPostalCode());
        target.setTelephone(source.getPhone());
        target.setAddress(source.getAddress());
        if(source.getCountry()!=null) {
        	target.setCountry(countryService.getByCode(source.getCountry()));
        }

        if(source.getZone()!=null) {
            target.setZone(zoneService.getByCode(source.getZone()));
        }
        target.setState(source.getBilstateOther());

        return target;
	}

	@Override
	public TransactionType nextTransaction(Long orderId, MerchantStore store) {

		try {

			Order modelOrder = orderService.getOrder(orderId, store);

			if(modelOrder == null) {
				throw new ResourceNotFoundException("Order id [" + orderId + "] not found for store [" + store.getCode() + "]");
			}

			Transaction last = transactionService.lastTransaction(modelOrder, store);

			if(last == null) {
				return TransactionType.OK;
			} else if(last.getTransactionType().name().equals(TransactionType.AUTHORIZE.name())) {
				return TransactionType.CAPTURE;
			} else if(last.getTransactionType().name().equals(TransactionType.AUTHORIZECAPTURE.name())) {
				return TransactionType.REFUND;
			} else if(last.getTransactionType().name().equals(TransactionType.CAPTURE.name())) {
				return TransactionType.REFUND;
			} else if(last.getTransactionType().name().equals(TransactionType.REFUND.name())) {
				return TransactionType.OK;
			} else {
				return TransactionType.OK;
			}


		} catch(Exception e) {
			throw new ServiceRuntimeException("Error while getting last transaction for order [" + orderId + "]",e);
		}


	}

	@Override
	public List<ReadableTransaction> listTransactions(Long orderId, MerchantStore store) {
		Validate.notNull(orderId, "orderId must not be null");
		Validate.notNull(store, "MerchantStore must not be null");
		List<ReadableTransaction> trx = new ArrayList<ReadableTransaction>();
		try {
			Order modelOrder = orderService.getOrder(orderId, store);

			if(modelOrder == null) {
				throw new ResourceNotFoundException("Order id [" + orderId + "] not found for store [" + store.getCode() + "]");
			}

			List<Transaction> transactions = transactionService.listTransactions(modelOrder);

			ReadableTransaction transaction = null;
			ReadableTransactionPopulator trxPopulator = null;

			for(Transaction tr : transactions) {
				transaction = new ReadableTransaction();
				trxPopulator = new ReadableTransactionPopulator();

				trxPopulator.setOrderService(orderService);
				trxPopulator.setPricingService(pricingService);

				trxPopulator.populate(tr, transaction, store, store.getDefaultLanguage());
				trx.add(transaction);
			}

			return trx;

		} catch(Exception e) {
			LOGGER.error("Error while getting transactions for order [" + orderId + "] and store code [" + store.getCode() + "]");
			throw new ServiceRuntimeException("Error while getting transactions for order [" + orderId + "] and store code [" + store.getCode() + "]");
		}

	}

	@Override
	public void updateOrderStatus(Order order, OrderStatus newStatus, MerchantStore store) {

		// make sure we are changing to different that current status
		if (newStatus == null) {
			throw new com.salesmanager.core.business.services.order.IllegalOrderStatusTransitionException(order.getStatus(), null);
		}
		if (newStatus.equals(order.getStatus())) {
			return; // we have the same status, lets just return
		}
		OrderStatus oldStatus = order.getStatus();
		if (newStatus == OrderStatus.CANCELED) {
			throw new ConflictException("Use POST /private/orders/" + order.getId()
					+ "/cancel to cancel an order: it returns the payment and the stock");
		}
		if (newStatus == OrderStatus.REFUNDED) {
			throw new ConflictException("Use POST /private/orders/" + order.getId()
					+ "/refund to refund an order: a full refund sets REFUNDED");
		}
		if (!OrderCommandService.STATUS_UPDATE_TARGETS.contains(newStatus)) {
			throw new ConflictException(newStatus + " is set by checkout and payment reconciliation only; use POST"
					+ " /private/orders/" + order.getId() + "/payment/recheck or /cancel");
		}
		// rejects anything not in the transition table (IllegalOrderStatusTransitionException -> 409)
		OrderStatusTransitions.check(oldStatus, newStatus);
		order.setStatus(newStatus);
		OrderStatusHistory history = new OrderStatusHistory();

		history.setComments( messages.getMessage("email.order.status.changed", new String[] {String.valueOf(oldStatus),
				newStatus.name()}, LocaleUtils.getLocale(store)));
		history.setCustomerNotified(emailsEnabled() ? 1 : 0);
		history.setStatus(newStatus);
		history.setDateAdded(new Date() );

		try {
			orderService.addOrderStatusHistory(order, history);
		} catch (ServiceException e) {
			// the change was not saved; answering 200 here would tell the admin it was
			throw new ServiceRuntimeException("Status of order [" + order.getId() + "] not saved", e);
		}
		notifyStatus(order, store, history.getComments());

	}
}
