package com.salesmanager.shop.store.controller.order.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.order.IllegalOrderStatusTransitionException;
import com.salesmanager.core.business.services.order.OrderService;
import com.salesmanager.core.business.services.order.idempotency.IdempotencyConflictException;
import com.salesmanager.core.business.services.order.idempotency.OrderIdempotencyService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.idempotency.OrderIdempotencyKey;
import com.salesmanager.core.model.order.orderstatus.OrderStatus;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.model.order.v1.PersistableOrder;

class OrderFacadeIdempotencyAndStatusTest {

	private static final String KEY = "key-123";
	private static final String CART = "cart-abc";

	private OrderFacadeImpl facade;
	private OrderService orderService;
	private OrderIdempotencyService idempotency;

	private final PersistableOrder request = new PersistableOrder();
	private final Customer customer = new Customer();
	private final MerchantStore store = new MerchantStore();
	private final Language language = new Language();
	private final Locale locale = Locale.CANADA;

	@BeforeEach
	void setUp() throws Exception {
		store.setId(1);
		facade = spy(new OrderFacadeImpl());
		orderService = mock(OrderService.class);
		idempotency = mock(OrderIdempotencyService.class);
		inject(facade, "orderService", orderService);
		inject(facade, "orderIdempotencyService", idempotency);
	}

	@Test
	void replayWithSameKeyReturnsSameOrderAndChargesOnce() throws Exception {
		Order created = new Order();
		created.setId(42L);
		// the non-idempotent checkout (which charges) is the 5-argument processOrder
		doReturn(created).when(facade).processOrder(request, customer, store, language, locale);

		OrderIdempotencyKey claimed = new OrderIdempotencyKey();
		claimed.setId(7L);
		ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
		when(idempotency.findKey(1, KEY)).thenReturn(null);
		when(idempotency.claimKey(eq(1), eq(KEY), eq(CART), hash.capture())).thenReturn(claimed);

		Order first = facade.processOrder(request, customer, store, language, locale, CART, KEY);
		assertSame(created, first);
		verify(idempotency).completeKey(7L, 42L);

		// second request: the key is now stored with the order id
		OrderIdempotencyKey stored = record(CART, hash.getValue(), 42L);
		when(idempotency.findKey(1, KEY)).thenReturn(stored);
		when(orderService.getOrder(42L, store)).thenReturn(created);

		Order second = facade.processOrder(request, customer, store, language, locale, CART, KEY);

		assertEquals(42L, second.getId());
		verify(facade, times(1)).processOrder(request, customer, store, language, locale);
		verify(idempotency, times(1)).claimKey(any(), any(), any(), any());
	}

	@Test
	void sameKeyForADifferentCartIsAConflict() throws Exception {
		when(idempotency.findKey(1, KEY))
				.thenReturn(record("other-cart", OrderFacadeImpl.requestHash(request), 42L));

		assertThrows(IdempotencyConflictException.class,
				() -> facade.processOrder(request, customer, store, language, locale, CART, KEY));

		verify(facade, never()).processOrder(request, customer, store, language, locale);
	}

	@Test
	void keyStillInFlightIsAConflict() throws Exception {
		when(idempotency.findKey(1, KEY)).thenReturn(record(CART, OrderFacadeImpl.requestHash(request), null));

		assertThrows(IdempotencyConflictException.class,
				() -> facade.processOrder(request, customer, store, language, locale, CART, KEY));
		verify(facade, never()).processOrder(request, customer, store, language, locale);
	}

	@Test
	void failedCheckoutReleasesTheKey() throws Exception {
		OrderIdempotencyKey claimed = new OrderIdempotencyKey();
		claimed.setId(7L);
		when(idempotency.claimKey(eq(1), eq(KEY), eq(CART), anyString())).thenReturn(claimed);
		org.mockito.Mockito.doThrow(new ServiceException(ServiceException.EXCEPTION_PAYMENT_DECLINED, "declined"))
				.when(facade).processOrder(request, customer, store, language, locale);

		assertThrows(ServiceException.class,
				() -> facade.processOrder(request, customer, store, language, locale, CART, KEY));
		verify(idempotency).releaseKey(7L);
		verify(idempotency, never()).completeKey(anyLong(), anyLong());
	}

	@Test
	void illegalStatusTransitionIsRejected() throws Exception {
		Order order = new Order();
		order.setId(5L);
		order.setStatus(OrderStatus.CANCELED);

		assertThrows(IllegalOrderStatusTransitionException.class,
				() -> facade.updateOrderStatus(order, OrderStatus.PROCESSED, store));

		assertEquals(OrderStatus.CANCELED, order.getStatus());
		verify(orderService, never()).addOrderStatusHistory(any(), any());
	}

	private static OrderIdempotencyKey record(String cart, String hash, Long orderId) {
		OrderIdempotencyKey k = new OrderIdempotencyKey();
		k.setId(7L);
		k.setMerchantId(1);
		k.setIdempotencyKey(KEY);
		k.setCartCode(cart);
		k.setRequestHash(hash);
		k.setOrderId(orderId);
		return k;
	}

	private static void inject(Object target, String name, Object value) throws Exception {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				f.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
