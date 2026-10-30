package com.salesmanager.core.business.repositories.catalog.product;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;

import org.junit.Test;

/**
 * The slug lookup receives new Locale(code). Java turns he, id and yi into the legacy
 * iw, in and ji, so the lookup maps them back to the codes stored in LANGUAGE.CODE.
 */
public class ProductSlugLanguageCodeTest {

	@Test
	public void legacyJavaCodesMapBackToStoredCodes() {
		assertEquals("he", ProductRepositoryImpl.languageCode(new Locale("he")));
		assertEquals("id", ProductRepositoryImpl.languageCode(new Locale("id")));
		assertEquals("yi", ProductRepositoryImpl.languageCode(new Locale("yi")));
	}

	@Test
	public void otherCodesPassThrough() {
		assertEquals("en", ProductRepositoryImpl.languageCode(new Locale("en")));
		assertEquals("fr", ProductRepositoryImpl.languageCode(new Locale("fr", "CA")));
		assertNull(ProductRepositoryImpl.languageCode(new Locale("")));
		assertNull(ProductRepositoryImpl.languageCode(null));
	}
}
