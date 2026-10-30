package com.salesmanager.core.business.services.customer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.salesmanager.core.business.repositories.customer.CustomerRepository;
import com.salesmanager.core.model.customer.Customer;

/**
 * The same email can be registered in several stores, so a lookup by nick
 * alone must not fail with a non-unique result.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceGetByNickTest {

	@Mock private CustomerRepository customerRepository;

	@Test
	void returnsTheCustomerWhenTheNickIsUnique() {
		Customer customer = new Customer();
		when(customerRepository.countByNick("a@test.com")).thenReturn(1L);
		when(customerRepository.findByNick("a@test.com")).thenReturn(customer);

		assertSame(customer, new CustomerServiceImpl(customerRepository).getByNick("a@test.com"));
	}

	@Test
	void returnsNullWhenTheNickExistsInSeveralStores() {
		when(customerRepository.countByNick("a@test.com")).thenReturn(2L);

		assertNull(new CustomerServiceImpl(customerRepository).getByNick("a@test.com"));
		verify(customerRepository, never()).findByNick("a@test.com");
	}

	@Test
	void returnsNullWhenTheNickIsUnknown() {
		when(customerRepository.countByNick("a@test.com")).thenReturn(0L);

		assertNull(new CustomerServiceImpl(customerRepository).getByNick("a@test.com"));
		verify(customerRepository, never()).findByNick("a@test.com");
	}
}
