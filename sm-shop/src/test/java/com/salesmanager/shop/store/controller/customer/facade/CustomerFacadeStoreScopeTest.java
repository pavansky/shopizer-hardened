package com.salesmanager.shop.store.controller.customer.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import com.salesmanager.core.business.services.customer.CustomerService;
import com.salesmanager.core.business.services.customer.review.CustomerReviewService;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.shop.model.customer.PersistableCustomer;
import com.salesmanager.shop.model.customer.PersistableCustomerReview;
import com.salesmanager.shop.populator.customer.CustomerPopulator;
import com.salesmanager.shop.store.api.exception.ResourceNotFoundException;

/**
 * Customer ids from a request are only resolved inside the caller's store, and
 * an update never moves a customer to another store.
 */
@ExtendWith(MockitoExtension.class)
class CustomerFacadeStoreScopeTest {

	@Mock private CustomerService customerService;
	@Mock private CustomerPopulator customerPopulator;
	@Mock private CustomerReviewService customerReviewService;
	@InjectMocks private CustomerFacadeImpl facade;

	private final MerchantStore storeA = store(1, "DEFAULT");
	private final MerchantStore storeB = store(2, "STORE_B");
	private final Language en = new Language();

	@BeforeEach
	void setUp() {
		storeA.setDefaultLanguage(en);
		storeB.setDefaultLanguage(en);
	}

	private static MerchantStore store(int id, String code) {
		MerchantStore s = new MerchantStore();
		s.setId(id);
		s.setCode(code);
		return s;
	}

	private static Customer customerOf(MerchantStore store, long id) {
		Customer c = new Customer();
		c.setId(id);
		c.setMerchantStore(store);
		return c;
	}

	@Test
	void readOfAnotherStoresCustomerIsNotFound() {
		when(customerService.getById(42L, storeA)).thenReturn(null);
		assertThrows(ResourceNotFoundException.class, () -> facade.getCustomerById(42L, storeA, en));
		verify(customerService, never()).getById(anyLong());
	}

	@Test
	void updateOfAnotherStoresCustomerIsNotFoundAndNothingIsSaved() throws Exception {
		when(customerService.getById(42L, storeA)).thenReturn(null);
		PersistableCustomer change = new PersistableCustomer();
		change.setId(42L);

		assertThrows(ResourceNotFoundException.class, () -> facade.update(change, storeA));
		verify(customerPopulator, never()).populate(any(), any(Customer.class), any(), any());
		verify(customerService, never()).save(any());
	}

	@Test
	void addressChangeOfAnotherStoresCustomerIsNotFound() throws Exception {
		when(customerService.getById(42L, storeA)).thenReturn(null);
		PersistableCustomer change = new PersistableCustomer();
		change.setId(42L);

		assertThrows(ResourceNotFoundException.class, () -> facade.updateAddress(change, storeA));
		verify(customerService, never()).saveOrUpdate(any());
	}

	@Test
	void deleteOfAnotherStoresCustomerIsNotFoundAndNothingIsDeleted() throws Exception {
		when(customerService.getById(42L, storeA)).thenReturn(null);
		assertThrows(ResourceNotFoundException.class, () -> facade.deleteById(42L, storeA));
		verify(customerService, never()).delete(any());
	}

	@Test
	void customerReviewsOfAnotherStoresCustomerAreNotFound() {
		when(customerService.getById(42L, storeA)).thenReturn(null);
		assertThrows(ResourceNotFoundException.class,
				() -> facade.getAllCustomerReviewsByReviewed(42L, storeA, en));
		assertThrows(ResourceNotFoundException.class,
				() -> facade.deleteCustomerReview(42L, 1L, storeA, en));
	}

	@Test
	void updateNeverMovesTheCustomerToAnotherStore() throws Exception {
		Customer existing = customerOf(storeB, 42L);
		when(customerService.getById(42L, storeB)).thenReturn(existing);
		// the populator assigns the store it is given; simulate it receiving another one
		doAnswer(inv -> {
			((Customer) inv.getArgument(1)).setMerchantStore(storeA);
			return inv.getArgument(1);
		}).when(customerPopulator).populate(any(PersistableCustomer.class), eq(existing), eq(storeB), any());

		PersistableCustomer change = new PersistableCustomer();
		change.setId(42L);
		facade.update(change, storeB);

		assertSame(storeB, existing.getMerchantStore());
		verify(customerService).save(existing);
	}

	/** Stops the facade at the populator and records the id it was handed. */
	private static class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	@Test
	void createIgnoresAnIdInTheBody() throws Exception {
		AtomicReference<Long> idSeen = new AtomicReference<>(-1L);
		doAnswer(inv -> {
			idSeen.set(((PersistableCustomer) inv.getArgument(0)).getId());
			throw new Stop();
		}).when(customerPopulator).populate(any(PersistableCustomer.class), any(Customer.class), eq(storeA), any());

		PersistableCustomer body = new PersistableCustomer();
		body.setId(42L);
		body.setEmailAddress("new@test.com");

		assertThrows(Stop.class, () -> facade.create(body, storeA, en));
		assertNull(idSeen.get());
		verify(customerService, never()).save(any());
		verify(customerService, never()).saveOrUpdate(any());
	}

	@Test
	void registrationIgnoresAnIdInTheBody() throws Exception {
		AtomicReference<Long> idSeen = new AtomicReference<>(-1L);
		doAnswer(inv -> {
			idSeen.set(((PersistableCustomer) inv.getArgument(0)).getId());
			throw new Stop();
		}).when(customerPopulator).populate(any(PersistableCustomer.class), eq(storeA), any());

		PersistableCustomer body = new PersistableCustomer();
		body.setId(42L);
		body.setUserName("new@test.com");
		body.setEmailAddress("new@test.com");

		assertThrows(Stop.class, () -> facade.registerCustomer(body, storeA, en));
		assertNull(idSeen.get());
		verify(customerService, never()).saveOrUpdate(any());
	}

	@Test
	void reviewByACustomerOfAnotherStoreIsNotFoundAndNothingIsSaved() throws Exception {
		when(customerService.getById(1L, storeA)).thenReturn(customerOf(storeA, 1L));
		when(customerService.getById(42L, storeA)).thenReturn(null);

		PersistableCustomerReview review = new PersistableCustomerReview();
		review.setId(7L);
		review.setCustomerId(42L);
		review.setRating(4d);

		assertThrows(ResourceNotFoundException.class, () -> facade.createCustomerReview(1L, review, storeA, en));
		assertNull(review.getId());
		verify(customerReviewService, never()).create(any());
	}

	@Test
	void reviewIdInTheBodyIsIgnoredOnCreate() throws Exception {
		when(customerService.getById(1L, storeA)).thenReturn(customerOf(storeA, 1L));
		when(customerService.getById(2L, storeA)).thenReturn(customerOf(storeA, 2L));
		when(customerReviewService.getByReviewerAndReviewed(2L, 1L)).thenThrow(new Stop());

		PersistableCustomerReview review = new PersistableCustomerReview();
		review.setId(7L);
		review.setCustomerId(2L);
		review.setRating(4d);

		assertThrows(Stop.class, () -> facade.createCustomerReview(1L, review, storeA, en));
		assertNull(review.getId());
		assertEquals(Long.valueOf(2L), review.getCustomerId());
	}
}
