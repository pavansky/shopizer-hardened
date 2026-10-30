package com.salesmanager.shop.store.api.v1.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.salesmanager.core.business.services.catalog.pricing.PricingService;
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
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.shipping.Quote;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCart;
import com.salesmanager.core.business.modules.integration.payment.impl.Stripe3Payment;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.address.Address;
import com.salesmanager.shop.model.order.transaction.ReadableTransaction;
import com.salesmanager.shop.populator.customer.CustomerPopulator;
import com.salesmanager.shop.store.api.exception.ConflictException;
import com.salesmanager.shop.store.api.exception.ConversionRuntimeException;
import com.salesmanager.shop.store.api.v1.order.OrderPaymentApi.PaymentInitRequest;

class OrderPaymentApiInitTest {

	private final OrderPaymentApi api = new OrderPaymentApi();
	private final MerchantStore store = new MerchantStore();
	private final Language language = new Language();
	private final ShoppingCart cart = new ShoppingCart();
	private PaymentService paymentService;
	private OrderService orderService;
	private ShippingQuoteService shippingQuoteService;
	private CustomerPopulator customerPopulator;

	@BeforeEach
	void setUp() throws Exception {
		ShoppingCartService carts = mock(ShoppingCartService.class);
		PricingService pricing = mock(PricingService.class);
		paymentService = mock(PaymentService.class);
		orderService = mock(OrderService.class);
		shippingQuoteService = mock(ShippingQuoteService.class);
		customerPopulator = mock(CustomerPopulator.class);
		ReflectionTestUtils.setField(api, "customerPopulator", customerPopulator);
		ReflectionTestUtils.setField(api, "shoppingCartService", carts);
		ReflectionTestUtils.setField(api, "pricingService", pricing);
		ReflectionTestUtils.setField(api, "paymentService", paymentService);
		ReflectionTestUtils.setField(api, "orderService", orderService);
		ReflectionTestUtils.setField(api, "shippingQuoteService", shippingQuoteService);

		cart.setId(5L);
		cart.setShoppingCartCode("cart-1");
		cart.setLineItems(new HashSet<>());
		when(carts.getByCode("cart-1", store)).thenReturn(cart);
		when(pricing.getAmount(anyString())).thenAnswer(inv -> new BigDecimal((String) inv.getArgument(0)));
		OrderTotalSummary totals = new OrderTotalSummary();
		totals.setTotal(new BigDecimal("40.50"));
		when(orderService.caculateOrderTotal(any(OrderSummary.class), eq(store), eq(language))).thenReturn(totals);

		Transaction init = new Transaction();
		init.setAmount(new BigDecimal("40.50"));
		init.setTransactionDate(new Date());
		init.getTransactionDetails().put("TRANSACTIONID", "pi_new");
		init.getTransactionDetails().put("INTENTSECRET", "pi_new_secret_abc");
		when(paymentService.initTransaction(isNull(), any(Payment.class), eq(store))).thenReturn(init);
	}

	private static PaymentInitRequest stripe3(String amount) {
		PaymentInitRequest payment = new PaymentInitRequest();
		payment.setPaymentModule("stripe3");
		payment.setPaymentType("CREDITCARD");
		payment.setTransactionType("INIT");
		payment.setAmount(amount);
		return payment;
	}

	@Test
	void intentAmountIsTheServerSideCartTotal() throws Exception {
		ReadableTransaction result = api.init(stripe3("40.50"), "cart-1", null, store, language);

		ArgumentCaptor<Payment> sent = ArgumentCaptor.forClass(Payment.class);
		verify(paymentService).initTransaction(isNull(), sent.capture(), eq(store));
		assertEquals(new BigDecimal("40.50"), sent.getValue().getAmount());
		assertEquals("cart-1", sent.getValue().getPaymentMetaData().get(Stripe3Payment.META_CART_CODE));
		// the client secret reaches the browser in the details, it is not stored
		assertTrue(result.getDetails().contains("pi_new_secret_abc"));
	}

	@Test
	void mismatchedClientAmountIsRejectedBeforeAnyIntentIsCreated() throws Exception {
		assertThrows(ConflictException.class, () -> api.init(stripe3("1.00"), "cart-1", null, store, language));

		verify(paymentService, never()).initTransaction(any(), any(Payment.class), any());
	}

	@Test
	void missingClientAmountUsesTheServerTotal() throws Exception {
		api.init(stripe3(null), "cart-1", null, store, language);

		ArgumentCaptor<Payment> sent = ArgumentCaptor.forClass(Payment.class);
		verify(paymentService).initTransaction(isNull(), sent.capture(), eq(store));
		assertEquals(new BigDecimal("40.50"), sent.getValue().getAmount());
	}

	private void quoteOfCart(long quoteId, long cartId) {
		Quote quote = new Quote();
		quote.setCartId(cartId);
		when(shippingQuoteService.getById(quoteId)).thenReturn(quote);
	}

	@Test
	void selectedShippingQuoteIsPartOfTheTotal() throws Exception {
		quoteOfCart(7L, 5L);
		ShippingSummary shipping = new ShippingSummary();
		when(shippingQuoteService.getShippingSummary(7L, store)).thenReturn(shipping);

		api.init(stripe3("40.50"), "cart-1", 7L, store, language);

		ArgumentCaptor<OrderSummary> summary = ArgumentCaptor.forClass(OrderSummary.class);
		verify(orderService).caculateOrderTotal(summary.capture(), eq(store), eq(language));
		assertSame(shipping, summary.getValue().getShippingSummary());
	}

	@Test
	void unknownShippingQuoteIsABadRequestNotAServerError() throws Exception {
		// getById hands back a lazy reference that fails on first access
		when(shippingQuoteService.getById(99L)).thenThrow(new javax.persistence.EntityNotFoundException("no quote 99"));

		ConversionRuntimeException e = assertThrows(ConversionRuntimeException.class,
				() -> api.init(stripe3("40.50"), "cart-1", 99L, store, language));

		assertTrue(e.getErrorMessage().contains("does not exist"));
		verify(shippingQuoteService, never()).getShippingSummary(any(), any());
		verify(paymentService, never()).initTransaction(any(), any(Payment.class), any());
	}

	@Test
	void quoteOfAnotherCartIsRejected() throws Exception {
		quoteOfCart(8L, 6L);

		assertThrows(ConversionRuntimeException.class, () -> api.init(stripe3("40.50"), "cart-1", 8L, store, language));

		verify(shippingQuoteService, never()).getShippingSummary(any(), any());
		verify(paymentService, never()).initTransaction(any(), any(Payment.class), any());
	}

	@Test
	void guestTotalIsComputedWithTheGuestAddressAsCheckoutDoes() throws Exception {
		// a store taxing by billing address: the guest's province adds tax to the total
		Customer withAddress = new Customer();
		when(customerPopulator.populate(any(PersistableCustomer.class), any(Customer.class), eq(store), eq(language)))
				.thenReturn(withAddress);
		OrderTotalSummary taxed = new OrderTotalSummary();
		taxed.setTotal(new BigDecimal("45.77"));
		when(orderService.caculateOrderTotal(any(OrderSummary.class), eq(withAddress), eq(store), eq(language)))
				.thenReturn(taxed);
		PaymentInitRequest request = stripe3("45.77");
		PersistableCustomer guest = new PersistableCustomer();
		Address billing = new Address();
		billing.setCountry("CA");
		billing.setZone("ON");
		guest.setBilling(billing);
		guest.setPassword("not-used-here");
		request.setCustomer(guest);

		api.init(request, "cart-1", null, store, language);

		ArgumentCaptor<PersistableCustomer> converted = ArgumentCaptor.forClass(PersistableCustomer.class);
		verify(customerPopulator).populate(converted.capture(), any(Customer.class), eq(store), eq(language));
		assertSame(billing, converted.getValue().getBilling());
		// only the addresses are converted: no password, nothing to persist
		assertEquals(null, converted.getValue().getPassword());
		ArgumentCaptor<Payment> sent = ArgumentCaptor.forClass(Payment.class);
		verify(paymentService).initTransaction(isNull(), sent.capture(), eq(store));
		assertEquals(new BigDecimal("45.77"), sent.getValue().getAmount());
		verify(orderService, never()).caculateOrderTotal(any(OrderSummary.class), eq(store), eq(language));
	}

	@Test
	void guestAmountWithoutTheTaxOfTheirAddressIsRejected() throws Exception {
		Customer withAddress = new Customer();
		when(customerPopulator.populate(any(PersistableCustomer.class), any(Customer.class), eq(store), eq(language)))
				.thenReturn(withAddress);
		OrderTotalSummary taxed = new OrderTotalSummary();
		taxed.setTotal(new BigDecimal("45.77"));
		when(orderService.caculateOrderTotal(any(OrderSummary.class), eq(withAddress), eq(store), eq(language)))
				.thenReturn(taxed);
		PaymentInitRequest request = stripe3("40.50");
		PersistableCustomer guest = new PersistableCustomer();
		guest.setBilling(new Address());
		request.setCustomer(guest);

		assertThrows(ConflictException.class, () -> api.init(request, "cart-1", null, store, language));

		verify(paymentService, never()).initTransaction(any(), any(Payment.class), any());
	}
}
