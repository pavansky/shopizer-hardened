package com.salesmanager.shop.store.api.v1.order;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.helper.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.shoppingcart.ShoppingCartService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.OrderCriteria;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.shoppingcart.ShoppingCart;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.ReadableCustomer;
import com.salesmanager.shop.model.order.ReadableOrderLifecycle;
import com.salesmanager.shop.model.order.v0.ReadableOrder;
import com.salesmanager.shop.model.order.v0.ReadableOrderList;
import com.salesmanager.shop.model.order.v1.PersistableAnonymousOrder;
import com.salesmanager.shop.model.order.v1.PersistableOrder;
import com.salesmanager.shop.model.order.v1.ReadableOrderConfirmation;
import com.salesmanager.shop.populator.customer.ReadableCustomerPopulator;
import com.salesmanager.core.business.services.order.IllegalOrderStatusTransitionException;
import com.salesmanager.core.business.services.order.PaymentConfirmationPendingException;
import com.salesmanager.core.business.services.order.idempotency.IdempotencyConflictException;
import com.salesmanager.shop.store.api.exception.ErrorEntity;
import com.salesmanager.shop.store.api.exception.GenericRuntimeException;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.exception.ServiceRuntimeException;
import com.salesmanager.shop.store.controller.customer.facade.CustomerFacade;
import com.salesmanager.shop.store.controller.order.facade.OrderFacade;
import com.salesmanager.shop.store.security.services.CredentialsException;
import com.salesmanager.shop.store.security.services.CredentialsService;
import com.salesmanager.shop.utils.AuthorizationUtils;
import com.salesmanager.shop.utils.LocaleUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.SwaggerDefinition;
import io.swagger.annotations.Tag;
import springfox.documentation.annotations.ApiIgnore;

@RestController
@RequestMapping("/api/v1")
@Api(tags = { "Ordering api (Order Flow Api)" })
@SwaggerDefinition(tags = { @Tag(name = "Order flow resource", description = "Manage orders (create, list, get)") })
public class OrderApi {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderApi.class);

	@Inject
	private CustomerService customerService;

	@Inject
	private OrderFacade orderFacade;

	@Inject
	private OrderService orderService;

	@Inject
	private com.salesmanager.shop.store.controller.order.facade.v1.OrderFacade orderFacadeV1;

	@Inject
	private ShoppingCartService shoppingCartService;

	@Autowired
	private CustomerFacade customerFacade;

	@Autowired
	private CustomerFacade customerFacadev1; //v1 version

	@Inject
	private AuthorizationUtils authorizationUtils;

	@Inject
	private CredentialsService credentialsService;

	private static final String DEFAULT_ORDER_LIST_COUNT = "25";

	/**
	 * Get a list of orders for a given customer accept request parameter
	 * 'start' start index for count accept request parameter 'max' maximum
	 * number count, otherwise returns all Used for administrators
	 *
	 * @param response
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = { "/private/orders/customers/{id}" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderList list(@PathVariable final Long id,
			@RequestParam(value = "start", required = false) Integer start,
			@RequestParam(value = "count", required = false) Integer count, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language, HttpServletResponse response) throws Exception {

		authorizeOrderReader(merchantStore);

		Customer customer = customerService.getById(id);
		// a customer of another store is reported as missing, so ids of other stores are not confirmed
		if (customer != null && (customer.getMerchantStore() == null
				|| !merchantStore.getId().equals(customer.getMerchantStore().getId()))) {
			customer = null;
		}

		if (customer == null) {
			LOGGER.error("Customer is null for id " + id);
			response.sendError(404, "Customer is null for id " + id);
			return null;
		}

		if (start == null) {
			start = new Integer(0);
		}
		if (count == null) {
			count = new Integer(100);
		}

		ReadableCustomer readableCustomer = new ReadableCustomer();
		ReadableCustomerPopulator customerPopulator = new ReadableCustomerPopulator();
		customerPopulator.populate(customer, readableCustomer, merchantStore, language);

		ReadableOrderList returnList = orderFacade.getReadableOrderList(merchantStore, customer, start, count,
				language);

		List<ReadableOrder> orders = returnList.getOrders();

		if (!CollectionUtils.isEmpty(orders)) {
			for (ReadableOrder order : orders) {
				order.setCustomer(readableCustomer);
			}
		}

		return returnList;
	}

	/**
	 * List orders for authenticated customers
	 *
	 * @param start
	 * @param count
	 * @param request
	 * @param response
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = { "/auth/orders" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderList list(@RequestParam(value = "page", required = false) Integer page,
			@RequestParam(value = "count", required = false) Integer count, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language, HttpServletRequest request, HttpServletResponse response) throws Exception {

		Principal principal = request.getUserPrincipal();
		String userName = principal.getName();

		Customer customer = customerService.getByNick(userName, merchantStore.getId());

		if (customer == null) {
			response.sendError(401, "Error while listing orders, customer not authorized");
			return null;
		}

		if (page == null) {
			page = new Integer(0);
		}
		if (count == null) {
			count = new Integer(100);
		}

		ReadableCustomer readableCustomer = new ReadableCustomer();
		ReadableCustomerPopulator customerPopulator = new ReadableCustomerPopulator();
		customerPopulator.populate(customer, readableCustomer, merchantStore, language);

		ReadableOrderList returnList = orderFacade.getReadableOrderList(merchantStore, customer, page, count, language);

		if (returnList == null) {
			returnList = new ReadableOrderList();
		}

		List<ReadableOrder> orders = returnList.getOrders();
		if (!CollectionUtils.isEmpty(orders)) {
			for (ReadableOrder order : orders) {
				order.setCustomer(readableCustomer);
			}
		}
		return returnList;
	}

	/**
	 * This method returns list of all the orders for a store.This is not
	 * bound to any specific stores and will get list of all the orders
	 * available for this instance
	 *
	 * @param start
	 * @param count
	 * @return List of orders
	 * @throws Exception
	 */
	@RequestMapping(value = { "/private/orders" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public ReadableOrderList list(
			@RequestParam(value = "count", required = false, defaultValue = DEFAULT_ORDER_LIST_COUNT) Integer count,
			@RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
			@RequestParam(value = "name", required = false) String name,
			@RequestParam(value = "id", required = false) Long id,
			@RequestParam(value = "status", required = false) String status,
			@RequestParam(value = "phone", required = false) String phone,
			@RequestParam(value = "email", required = false) String email,
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		OrderCriteria orderCriteria = new OrderCriteria();
		orderCriteria.setPageSize(count);
		orderCriteria.setStartPage(page);

		orderCriteria.setCustomerName(name);
		orderCriteria.setCustomerPhone(phone);
		orderCriteria.setStatus(status);
		orderCriteria.setEmail(email);
		orderCriteria.setId(id);


		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER, Constants.GROUP_ADMIN_RETAIL).collect(Collectors.toList()), merchantStore);

		ReadableOrderList orders = orderFacade.getReadableOrderList(orderCriteria, merchantStore);


		return orders;

	}

	/**
	 * Order details
	 * @param id
	 * @param merchantStore
	 * @param language
	 * @return
	 */
	@RequestMapping(value = { "/private/orders/{id}" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrder get(
			@PathVariable final Long id,
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER, Constants.GROUP_ADMIN_RETAIL).collect(Collectors.toList()), merchantStore);


		ReadableOrder order = orderFacade.getReadableOrder(id, merchantStore, language);

		return order;
	}

	/**
	 * Get a given order by id
	 *
	 * @param id
	 * @param request
	 * @param response
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = { "/auth/orders/{id}" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrder getOrder(@PathVariable final Long id, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language, HttpServletRequest request, HttpServletResponse response) throws Exception {
		Principal principal = request.getUserPrincipal();
		String userName = principal.getName();

		Customer customer = customerService.getByNick(userName, merchantStore.getId());

		if (customer == null) {
			response.sendError(401, "Error while getting the order, customer not authorized");
			return null;
		}

		ReadableOrder order = orderFacade.getReadableOrder(id, merchantStore, language);

		if (order == null) {
			LOGGER.error("Order is null for id " + id);
			response.sendError(404, "Order is null for id " + id);
			return null;
		}

		if (order.getCustomer() == null) {
			LOGGER.error("Order is null for customer " + principal);
			response.sendError(404, "Order is null for customer " + principal);
			return null;
		}

		// an order without a customer id cannot be shown as anyone's own order
		if (order.getCustomer().getId() == null
				|| order.getCustomer().getId().longValue() != customer.getId().longValue()) {
			LOGGER.error("Order is null for customer " + principal);
			response.sendError(404, "Order is null for customer " + principal);
			return null;
		}

		return order;
	}

	/**
	 * Action for performing a checkout on a given shopping cart
	 *
	 * @param id
	 * @param order
	 * @param request
	 * @param response
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = { "/auth/cart/{code}/checkout" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderConfirmation checkout(
			@PathVariable final String code, //shopping cart
			@Valid @RequestBody PersistableOrder order, // order
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request,
			HttpServletResponse response, Locale locale) throws Exception {

		try {
			Principal principal = request.getUserPrincipal();
			String userName = principal.getName();

			Customer customer = customerService.getByNick(userName);

			if (customer == null) {
				response.sendError(401, "Error while performing checkout customer not authorized");
				return null;
			}

			ShoppingCart cart = shoppingCartService.getByCode(code, merchantStore);
			if (cart == null) {
				throw new ResourceNotFoundException("Cart code " + code + " does not exist");
			}

			order.setShoppingCartId(cart.getId());
			order.setCustomerId(customer.getId());//That is an existing customer purchasing

			Order modelOrder = orderFacade.processOrder(order, customer, merchantStore, language, locale, code,
					idempotencyKey);
			Long orderId = modelOrder.getId();
			modelOrder.setId(orderId);


			return orderFacadeV1.orderConfirmation(modelOrder, customer, merchantStore, language);



		} catch (IdempotencyConflictException | ResourceNotFoundException e) {
			throw e;
		} catch (PaymentConfirmationPendingException e) {
			throw new PaymentBeingConfirmed(e.getOrderId());
		} catch (Exception e) {
			rethrowIfOutOfStock(e);
			LOGGER.error("Error while processing checkout", e);
			try {
				response.sendError(503, "Error while processing checkout " + e.getMessage());
			} catch (Exception ignore) {
			}
			return null;
		}
	}

	/**
	 * Main checkout resource that will complete the order flow
	 * @param code
	 * @param order
	 * @param merchantStore
	 * @param language
	 * @return
	 */
	@RequestMapping(value = { "/cart/{code}/checkout" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderConfirmation checkout(
			@PathVariable final String code,//shopping cart
			@Valid @RequestBody PersistableAnonymousOrder order,//order
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

		Validate.notNull(order.getCustomer(), "Customer must not be null");


		ShoppingCart cart;
		try {
			cart = shoppingCartService.getByCode(code, merchantStore);

			if (cart == null) {
				throw new ResourceNotFoundException("Cart code " + code + " does not exist");
			}

			//security password validation
			PersistableCustomer presistableCustomer = order.getCustomer();
			if(!StringUtils.isBlank(presistableCustomer.getPassword())) { //validate customer password
				credentialsService.validateCredentials(presistableCustomer.getPassword(), presistableCustomer.getRepeatPassword(), merchantStore, language);
			}

			Customer customer = new Customer();
			customer = customerFacade.populateCustomerModel(customer, order.getCustomer(), merchantStore, language);

			if(!StringUtils.isBlank(presistableCustomer.getPassword())) {
				//check if customer already exist
				customer.setAnonymous(false);
				customer.setNick(customer.getEmailAddress()); //username
				if(customerFacadev1.checkIfUserExists(customer.getNick(),  merchantStore)) {
					//409 Conflict
					throw new GenericRuntimeException("409", "Customer with email [" + customer.getEmailAddress() + "] is already registered");
				}
			}


			order.setShoppingCartId(cart.getId());

			Order modelOrder = orderFacade.processOrder(order, customer, merchantStore, language,
					LocaleUtils.getLocale(language), code, idempotencyKey);
			Long orderId = modelOrder.getId();
			//populate order confirmation
			order.setId(orderId);
			// set customer id
			order.getCustomer().setId(modelOrder.getCustomerId());

			return orderFacadeV1.orderConfirmation(modelOrder, customer, merchantStore, language);


		} catch (IdempotencyConflictException e) {
			throw e;
		} catch (PaymentConfirmationPendingException e) {
			throw new PaymentBeingConfirmed(e.getOrderId());
		} catch (Exception e) {
			rethrowIfOutOfStock(e);
			if(e instanceof CredentialsException) {
				throw new GenericRuntimeException("412","Credentials creation Failed [" + e.getMessage() + "]");
			}
			String message = e.getMessage();
			if(StringUtils.isBlank(message)) {//exception type
				message = "APP-BACKEND";
				if(e.getCause() instanceof com.salesmanager.core.modules.integration.IntegrationException) {
					message = "Integration problen occured to complete order";
				}
			}
			throw new ServiceRuntimeException("Error during checkout [" + message + "]", e);
		}

	}

	@RequestMapping(value = { "/private/orders/{id}/customer" }, method = RequestMethod.PATCH)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({
			@ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public void updateOrderCustomer(
			@PathVariable final Long id,
			@Valid @RequestBody PersistableCustomer orderCustomer,
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER, Constants.GROUP_ADMIN_RETAIL).collect(Collectors.toList()), merchantStore);


		orderFacade.updateOrderCustomre(id, orderCustomer, merchantStore);
		return;
	}

	@RequestMapping(value = { "/private/orders/{id}/status" }, method = RequestMethod.PUT)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({
			@ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public void updateOrderStatus(
			@PathVariable final Long id,
			@Valid @RequestBody String status,
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		authorizeOrderReader(merchantStore);

		Order order = orderService.getOrder(id, merchantStore);
		if (order == null) {
			throw new ResourceNotFoundException("Order id [" + id + "] not found for store [" + merchantStore.getCode() + "]");
		}

		OrderStatus statusEnum;
		try {
			statusEnum = OrderStatus.valueOf(StringUtils.trim(StringUtils.strip(status, "\"")));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new ServiceRuntimeException("400", "Unknown order status [" + status + "]");
		}

		orderFacade.updateOrderStatus(order, statusEnum, merchantStore);
		return;
	}

	/**
	 * Legal next statuses of an order: next (the transition table), statusUpdates
	 * (what PUT /status accepts), and whether cancel or a payment re-check apply.
	 * 404 when the order does not belong to the store.
	 */
	@RequestMapping(value = { "/private/orders/{id}/transitions" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({
			@ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderLifecycle transitions(@PathVariable final Long id, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {
		authorizeOrderReader(merchantStore);
		return orderFacade.getOrderLifecycle(id, merchantStore);
	}

	/**
	 * Cancels an order. Captured money still refundable is refunded, an
	 * authorisation is voided, a charge the gateway took on a PAYMENT_PENDING or
	 * PAYMENT_REVIEW order is returned, the stock goes back to inventory and the
	 * customer gets the status email. Optional body {"comment": "..."}.
	 * 404 when the order does not belong to the store, 409 when it is already
	 * canceled or cannot be canceled from its status.
	 */
	@RequestMapping(value = { "/private/orders/{id}/cancel" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({
			@ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderLifecycle cancel(@PathVariable final Long id,
			@RequestBody(required = false) CancelRequest cancel, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {
		authorizeMoneyMovement(merchantStore);
		return orderFacade.cancelOrder(id, cancel == null ? null : cancel.getComment(), merchantStore);
	}

	/**
	 * Asks the gateway again about a PAYMENT_PENDING or PAYMENT_REVIEW order and
	 * settles it when the answer is clear (paid: ORDERED or PROCESSED; not paid:
	 * PAYMENT_FAILED with the stock returned). Otherwise the order is left as it
	 * is and the message says so. 409 for an order in any other status.
	 */
	@RequestMapping(value = { "/private/orders/{id}/payment/recheck" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({
			@ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderLifecycle recheckPayment(@PathVariable final Long id, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {
		authorizeMoneyMovement(merchantStore);
		return orderFacade.recheckPayment(id, merchantStore);
	}

	/**
	 * A person confirms the payment of a PAYMENT_REVIEW order that the gateway
	 * cannot report (a money order that arrived, a charge seen in the gateway
	 * dashboard). Body {"reason": "...", "reference": "..."}; the reason is
	 * required and the history records it with the admin user. Records the
	 * payment, settles the order and sends the order confirmation. 409 for an
	 * order in any other status.
	 */
	@RequestMapping(value = { "/private/orders/{id}/payment/confirm" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({
			@ApiImplicitParam(name = "store", dataType = "string", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "string", defaultValue = "en") })
	public ReadableOrderLifecycle confirmPayment(@PathVariable final Long id,
			@RequestBody(required = false) ConfirmPaymentRequest confirm, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {
		authorizeMoneyMovement(merchantStore);
		String user = authorizationUtils.authenticatedUser();
		return orderFacade.confirmPayment(id, user, confirm == null ? null : confirm.getReference(),
				confirm == null ? null : confirm.getReason(), merchantStore);
	}

	/** Body of a payment confirmed by hand. */
	public static class ConfirmPaymentRequest {
		private String reason;
		private String reference;

		public String getReason() {
			return reason;
		}

		public void setReason(String reason) {
			this.reason = reason;
		}

		public String getReference() {
			return reference;
		}

		public void setReference(String reference) {
			this.reference = reference;
		}
	}

	/** Optional cancel request body. */
	public static class CancelRequest {
		private String comment;

		public String getComment() {
			return comment;
		}

		public void setComment(String comment) {
			this.comment = comment;
		}
	}

	private void authorizeOrderReader(MerchantStore merchantStore) {
		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER, Constants.GROUP_ADMIN_RETAIL).collect(Collectors.toList()), merchantStore);
	}

	/** Same groups as capture and refund: these commands move money. */
	private void authorizeMoneyMovement(MerchantStore merchantStore) {
		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER).collect(Collectors.toList()), merchantStore);
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	@ResponseBody
	public ErrorEntity handleIdempotencyConflict(IdempotencyConflictException e) {
		return error("409", e.getMessage());
	}

	/**
	 * The gateway did not answer clearly. The order exists in PAYMENT_PENDING and
	 * the reconciler settles it with the gateway. 202 Accepted rather than an
	 * error: the request was taken and must not be retried as a new payment.
	 * The client polls the order by id.
	 */
	@ExceptionHandler(PaymentBeingConfirmed.class)
	@ResponseStatus(HttpStatus.ACCEPTED)
	@ResponseBody
	public ErrorEntity handlePaymentBeingConfirmed(PaymentBeingConfirmed e) {
		return error(PAYMENT_PENDING_CODE, e.getMessage());
	}

	static final String PAYMENT_PENDING_CODE = "payment_pending";

	/** Carries the pending order id from the checkout to the 202 handler. */
	static final class PaymentBeingConfirmed extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final Long orderId;

		PaymentBeingConfirmed(Long orderId) {
			super("Payment for order " + orderId + " is being confirmed. Do not pay again; check the order status.");
			this.orderId = orderId;
		}

		Long getOrderId() {
			return orderId;
		}
	}

	@ExceptionHandler(IllegalOrderStatusTransitionException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	@ResponseBody
	public ErrorEntity handleIllegalTransition(IllegalOrderStatusTransitionException e) {
		return error("409", e.getMessage());
	}

	private static ErrorEntity error(String code, String message) {
		ErrorEntity entity = new ErrorEntity();
		entity.setErrorCode(code);
		entity.setMessage(message);
		return entity;
	}

	/**
	 * Running out of stock is a business conflict, not a server error. The exception can
	 * arrive wrapped by the facade, so the cause chain is searched; the client gets 409
	 * with the reason instead of a 500 or 503.
	 */
	private static void rethrowIfOutOfStock(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof com.salesmanager.core.business.exception.ServiceException
					&& ((com.salesmanager.core.business.exception.ServiceException) t).getExceptionType()
							== com.salesmanager.core.business.exception.ServiceException.EXCEPTION_INVENTORY_MISMATCH) {
				throw new com.salesmanager.shop.store.api.exception.ConflictException(t.getMessage());
			}
			if (t.getCause() == t) {
				break;
			}
		}
	}
}
