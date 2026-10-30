package com.salesmanager.core.business.services.catalog.product;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;

import com.salesmanager.core.business.repositories.catalog.product.ProductRepository;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.ProductCriteria;
import com.salesmanager.core.model.catalog.product.ProductList;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;

/** listByStore(page, count) used to call setPageSize twice and never set the page. */
public class ProductServiceListingPageTest {

	@Test
	public void pageAndCountReachTheRepository() {
		ProductRepository repository = mock(ProductRepository.class);
		ProductList result = new ProductList();
		result.setTotalCount(42);
		when(repository.listByStore(any(MerchantStore.class), any(Language.class), any(ProductCriteria.class)))
				.thenReturn(result);
		ProductServiceImpl service = new ProductServiceImpl(repository);

		MerchantStore store = new MerchantStore();
		Language en = new Language("en");
		ProductCriteria criteria = new ProductCriteria();
		criteria.setStartPage(0);

		Page<Product> page = service.listByStore(store, en, criteria, 3, 15);

		ArgumentCaptor<ProductCriteria> sent = ArgumentCaptor.forClass(ProductCriteria.class);
		verify(repository).listByStore(eq(store), eq(en), sent.capture());
		assertEquals(3, sent.getValue().getStartPage());
		assertEquals(15, sent.getValue().getPageSize());
		assertFalse(sent.getValue().isLegacyPagination());
		assertEquals(42, page.getTotalElements());
		assertEquals(3, page.getNumber());
	}
}
