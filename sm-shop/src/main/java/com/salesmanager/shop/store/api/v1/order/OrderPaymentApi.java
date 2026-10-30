package com.salesmanager.shop.store.api.v1.order;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.salesmanager.core.business.modules.integration.payment.impl.Stripe3Payment;
import com.salesmanager.core.business.services.catalog.pricing.PricingService;
import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.payments.PaymentService;
import com.salesmanager.core.business.services.shipping.ShippingQuoteService;
import com.salesmanager.core.business.services.shoppingcart.ShoppingCartService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.order.OrderTotalSummary;
import com.salesmanager.core.model.payments.Payment;
import com.salesmanager.core.model.payments.Transaction;
import com.salesmanager.core.model.payments.TransactionType;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCart;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.model.order.transaction.PersistablePayment;
import com.salesmanager.shop.model.order.transaction.ReadableTransaction;
import com.salesmanager.shop.model.order.v0.ReadableOrderList;
import com.salesmanager.shop.populator.order.transaction.PersistablePaymentPopulator;
import com.salesmanager.shop.populator.order.transaction.ReadableTransactionPopulator;
import com.salesmanager.shop.store.api.exception.ConflictException;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;
import com.salesmanager.shop.store.api.v1.payment.PaymentErrorResolver;
import com.salesmanager.shop.store.controller.order.facade.OrderFacade;
import com.salesmanager.shop.utils.AuthorizationUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.SwaggerDefinition;
import io.swagger.annotations.Tag;
import springfox.documentation.annotations.ApiIgnore;

@Controller
@RequestMapping("/api/v1")
@Api(tags = { "Order payment resource (Order payment Api)" })
@SwaggerDefinition(tags = { @Tag(name = "Order payment resource", description = "Manage order payments") })
public class OrderPaymentApi {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderPaymentApi.class);

	@Inject
	private CustomerService customerService;

	@Inject
	private OrderService orderService;

	@Inject
	private ShoppingCartService shoppingCartService;

	@Inject
	private PricingService pricingService;

	@Inject
	private PaymentService paymentService;

	@Inject
	private OrderFacade orderFacade;

	@Inject
	private ShippingQuoteService shippingQuoteService;
	
	@Inject
	private AuthorizationUtils authorizationUtils;

	@RequestMapping(value = { "/cart/{code}/payment/init" }, method = RequestMethod.POST)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public ReadableTransaction init(@Valid @RequestBody PersistablePayment payment, @PathVariable String code,
			@RequestParam(value = "quote", required = false) Long quote,
			@ApiIgnore MerchantStore merchantStore, @ApiIgnore Language language) throws Exception {

		ShoppingCart cart = shoppingCartService.getByCode(code, merchantStore);
		if (cart == null) {
			throw new ResourceNotFoundException("Cart code " + code + " does not exist");
		}

		return initForCart(payment, cart, quote, null, merchantStore, language);

	}

	/**
	 * Starts a gateway payment (for Stripe, creates the PaymentIntent) for the cart.
	 * The amount is the server-side total of the cart and the optional shipping quote,
	 * computed as checkout computes it; the client amount is only compared with it, so
	 * a tampered or stale client total cannot create an intent for a different amount.
	 * The response details carry the gateway's client data (the PaymentIntent client
	 * secret), which is not stored.
	 */
	private ReadableTransaction initForCart(PersistablePayment payment, ShoppingCart cart, Long quote,
			Customer customer, MerchantStore merchantStore, Language language) throws Exception {

		BigDecimal total = cartTotal(cart, quote, customer, merchantStore, language);

		if (StringUtils.isBlank(payment.getAmount())) {
			payment.setAmount(total.toPlainString());
		}
		PersistablePaymentPopulator populator = new PersistablePaymentPopulator();
		populator.setPricingService(pricingService);

		Payment paymentModel = new Payment();

		populator.populate(payment, paymentModel, merchantStore, language);

		if (paymentModel.getAmount() == null || paymentModel.getAmount().compareTo(total) != 0) {
			throw new ConflictException("Payment amount " + payment.getAmount() + " does not match the cart total "
					+ total.toPlainString() + "; recalculate the order and start the payment again");
		}
		paymentModel.setAmount(total);
		paymentModel.getPaymentMetaData().put(Stripe3Payment.META_CART_CODE, cart.getShoppingCartCode());

		Transaction transactionModel = paymentService.initTransaction(customer, paymentModel, merchantStore);

		ReadableTransaction transaction = new ReadableTransaction();
		ReadableTransactionPopulator trxPopulator = new ReadableTransactionPopulator();
		trxPopulator.setOrderService(orderService);
		trxPopulator.setPricingService(pricingService);

		trxPopulator.populate(transactionModel, transaction, merchantStore, language);
		if (StringUtils.isBlank(transaction.getDetails())) {
			transaction.setDetails(transactionModel.toJSONString());
		}

		return transaction;
	}

	/** Cart total with the selected shipping quote, as checkout computes it. */
	BigDecimal cartTotal(ShoppingCart cart, Long quote, Customer customer, MerchantStore store, Language language)
			throws Exception {
		OrderSummary summary = new OrderSummary();
		if (quote != null && quote.longValue() > 0) {
			ShippingSummary shipping = shippingQuoteService.getShippingSummary(quote, store);
			if (shipping == null) {
				throw new ConflictException("Shipping quote " + quote + " is not valid for this cart");
			}
			summary.setShippingSummary(shipping);
		}
		summary.setProducts(new ArrayList<ShoppingCartItem>(cart.getLineItems()));
		OrderTotalSummary totals = customer == null ? orderService.caculateOrderTotal(summary, store, language)
				: orderService.caculateOrderTotal(summary, customer, store, language);
		return totals.getTotal();
	}

	@RequestMapping(value = { "/auth/cart/{code}/payment/init" }, method = RequestMethod.POST)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public ReadableTransaction init(@Valid @RequestBody PersistablePayment payment, @PathVariable String code,
			@RequestParam(value = "quote", required = false) Long quote,
			@ApiIgnore MerchantStore merchantStore, @ApiIgnore Language language, HttpServletRequest request,
			HttpServletResponse response) throws Exception {

		try {
			Principal principal = request.getUserPrincipal();
			String userName = principal.getName();

			Customer customer = customerService.getByNick(userName);

			if (customer == null) {
				response.sendError(401, "Error while initializing the payment customer not authorized");
				return null;
			}

			ShoppingCart cart = shoppingCartService.getByCode(code, merchantStore);
			if (cart == null) {

				throw new ResourceNotFoundException("Cart code " + code + " does not exist");
			}

			if (cart.getCustomerId() == null) {
				response.sendError(404, "Cart code " + code + " does not exist for exist for user " + userName);
				return null;
			}

			if (cart.getCustomerId().longValue() != customer.getId().longValue()) {
				response.sendError(404, "Cart code " + code + " does not exist for exist for user " + userName);
				return null;
			}

			return initForCart(payment, cart, quote, customer, merchantStore, language);

		} catch (Exception e) {
			if (e instanceof ConflictException || e instanceof ResourceNotFoundException
					|| PaymentErrorResolver.handles(e)) {
				throw e;
			}
			LOGGER.error("Error while initializing the payment", e);
			try {
				response.sendError(503, "Error while initializing the payment " + e.getMessage());
			} catch (Exception ignore) {
			}
			return null;
		}
	}

	@RequestMapping(value = { "/private/orders/{id}/payment/nextTransaction" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)

	@ResponseBody
	@ApiImplicitParams({ 
		    @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public String nextTransaction(
			@PathVariable final Long id, 
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER, Constants.GROUP_ADMIN_RETAIL).collect(Collectors.toList()), merchantStore);

		TransactionType transactionType = orderFacade.nextTransaction(id, merchantStore);
		return "{\"transactionType\":\"" + transactionType.name() + "\"}";

	}
	
	@RequestMapping(value = { "/private/orders/{id}/payment/transactions" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)

	@ResponseBody
	@ApiImplicitParams({ 
		    @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public List<ReadableTransaction> listTransactions(
			@PathVariable final Long id, 
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {

		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER, Constants.GROUP_ADMIN_RETAIL).collect(Collectors.toList()), merchantStore);


		return orderFacade.listTransactions(id, merchantStore);

	}

	/**
	 * An order can be pre-authorized but un captured. This metho returns all
	 * order subject to be capturable For a given time frame
	 *
	 * @param startDate
	 * @param endDate
	 * @param request
	 * @param response
	 * @return ReadableOrderList
	 * @throws Exception
	 */
	@RequestMapping(value = { "/private/orders/payment/capturable" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public ReadableOrderList listCapturableOrders(
			@RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@ApiIgnore MerchantStore merchantStore, @ApiIgnore Language language, HttpServletRequest request,
			HttpServletResponse response) {

		try {

			// if startdate or enddate are null use default range (last 24
			// hours) DD-1 to DD
			Calendar cal = Calendar.getInstance();
			Date sDate = null;

			if (startDate != null) {
				sDate = Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
			} else {
				cal.add(Calendar.DATE, -1);
				sDate = cal.getTime();
			}

			Date eDate = null;

			if (endDate != null) {
				eDate = Date.from(endDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
			} else {
				eDate = new Date();
			}

			ReadableOrderList returnList = orderFacade.getCapturableOrderList(merchantStore, sDate, eDate, language);

			return returnList;

		} catch (Exception e) {
			LOGGER.error("Error while getting capturable payments", e);
			try {
				response.sendError(503, "Error while getting capturable payments " + e.getMessage());
			} catch (Exception ignore) {
			}
			return null;
		}
	}

	private void authorizeOrderAdmin(MerchantStore merchantStore) {
		String user = authorizationUtils.authenticatedUser();
		authorizationUtils.authorizeUser(user, Stream.of(Constants.GROUP_SUPERADMIN, Constants.GROUP_ADMIN,
				Constants.GROUP_ADMIN_ORDER).collect(Collectors.toList()), merchantStore);
	}

	/**
	 * Captures the pending AUTHORIZE transaction of an order.
	 * 404 when the order does not belong to the store, 409 when nothing is capturable.
	 */
	@RequestMapping(value = { "/private/orders/{id}/capture" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public ReadableTransaction capturePayment(@PathVariable Long id, @ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {
		authorizeOrderAdmin(merchantStore);
		return orderFacade.captureOrder(merchantStore, id, language);
	}

	/**
	 * Refunds an order. The optional body {"amount": decimal} defaults to the
	 * full remaining refundable amount (captured minus already refunded).
	 * 400 for an invalid amount, 404 when the order does not belong to the
	 * store, 409 when nothing is refundable.
	 */
	@RequestMapping(value = { "/private/orders/{id}/refund" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	@ApiImplicitParams({ @ApiImplicitParam(name = "store", dataType = "String", defaultValue = "DEFAULT"),
			@ApiImplicitParam(name = "lang", dataType = "String", defaultValue = "en") })
	public ReadableTransaction refundPayment(@PathVariable Long id,
			@RequestBody(required = false) RefundRequest refund,
			@ApiIgnore MerchantStore merchantStore,
			@ApiIgnore Language language) {
		authorizeOrderAdmin(merchantStore);
		BigDecimal amount = refund == null ? null : refund.getAmount();
		return orderFacade.refundOrder(merchantStore, id, amount, language);
	}

	/** Optional refund request body. */
	public static class RefundRequest {

		private BigDecimal amount;

		public BigDecimal getAmount() {
			return amount;
		}

		public void setAmount(BigDecimal amount) {
			this.amount = amount;
		}
	}
}
