package com.salesmanager.test.catalog;

import java.math.BigDecimal;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.attribute.ProductAttribute;
import com.salesmanager.core.model.catalog.product.attribute.ProductOption;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionDescription;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionType;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionValue;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionValueDescription;
import com.salesmanager.core.model.catalog.product.description.ProductDescription;
import com.salesmanager.core.model.catalog.product.type.ProductType;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.language.Language;

/**
 * Deleting an option value must only remove the attributes that reference
 * that value, and must leave attributes of other values untouched.
 */
public class ProductOptionValueDeleteTest extends com.salesmanager.test.common.AbstractSalesManagerCoreTestCase {

	@Test
	public void testDeleteOptionValueRemovesOnlyItsAttributes() throws Exception {

		Language en = languageService.getByCode("en");
		MerchantStore store = merchantService.getByCode(MerchantStore.DEFAULT_STORE);
		ProductType generalType = productTypeService.getProductType(ProductType.GENERAL_TYPE);

		Product product = new Product();
		product.setSku("OVDEL-TEST-SKU");
		product.setType(generalType);
		product.setMerchantStore(store);
		product.setAvailable(true);

		ProductDescription description = new ProductDescription();
		description.setName("Option value delete test");
		description.setLanguage(en);
		description.setProduct(product);
		product.getDescriptions().add(description);

		productService.saveProduct(product);

		ProductOption optionA = createOption(store, en, "OVDEL_A");
		ProductOption optionB = createOption(store, en, "OVDEL_B");
		ProductOptionValue valueV = createValue(store, en, "ovdel_v");
		ProductOptionValue valueW = createValue(store, en, "ovdel_w");

		ProductAttribute av = createAttribute(product, optionA, valueV);
		ProductAttribute aw = createAttribute(product, optionA, valueW);
		ProductAttribute bw = createAttribute(product, optionB, valueW);

		//lookup by value id must return exactly the (A,V) attribute
		List<ProductAttribute> byValue = productAttributeService.getByOptionValueId(store, valueV.getId());
		Assert.assertEquals(1, byValue.size());
		Assert.assertEquals(av.getId(), byValue.get(0).getId());

		productOptionValueService.delete(valueV);

		List<ProductAttribute> byOptionA = productAttributeService.getByOptionId(store, optionA.getId());
		Assert.assertEquals(1, byOptionA.size());
		Assert.assertEquals(aw.getId(), byOptionA.get(0).getId());

		List<ProductAttribute> byOptionB = productAttributeService.getByOptionId(store, optionB.getId());
		Assert.assertEquals(1, byOptionB.size());
		Assert.assertEquals(bw.getId(), byOptionB.get(0).getId());

		Assert.assertNull(productOptionValueService.getById(store, valueV.getId()));
	}

	private ProductOption createOption(MerchantStore store, Language en, String code) throws Exception {
		ProductOption option = new ProductOption();
		option.setMerchantStore(store);
		option.setCode(code);
		option.setProductOptionType(ProductOptionType.Radio.name());

		ProductOptionDescription optionDescription = new ProductOptionDescription();
		optionDescription.setLanguage(en);
		optionDescription.setName(code);
		optionDescription.setDescription(code);
		optionDescription.setProductOption(option);
		option.getDescriptions().add(optionDescription);

		productOptionService.saveOrUpdate(option);
		return option;
	}

	private ProductOptionValue createValue(MerchantStore store, Language en, String code) throws Exception {
		ProductOptionValue value = new ProductOptionValue();
		value.setMerchantStore(store);
		value.setCode(code);

		ProductOptionValueDescription valueDescription = new ProductOptionValueDescription();
		valueDescription.setLanguage(en);
		valueDescription.setName(code);
		valueDescription.setDescription(code);
		valueDescription.setProductOptionValue(value);
		value.getDescriptions().add(valueDescription);

		productOptionValueService.saveOrUpdate(value);
		return value;
	}

	private ProductAttribute createAttribute(Product product, ProductOption option, ProductOptionValue value) throws Exception {
		ProductAttribute attribute = new ProductAttribute();
		attribute.setProduct(product);
		attribute.setProductOption(option);
		attribute.setProductOptionValue(value);
		attribute.setAttributeDefault(false);
		attribute.setProductAttributePrice(new BigDecimal(0));
		attribute.setProductAttributeWeight(new BigDecimal(0));

		productAttributeService.create(attribute);
		return attribute;
	}

}
