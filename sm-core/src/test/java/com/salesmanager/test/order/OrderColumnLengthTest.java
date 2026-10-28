package com.salesmanager.test.order;

import java.lang.reflect.Field;

import javax.persistence.Column;

import org.junit.Assert;
import org.junit.Test;

import com.salesmanager.core.model.common.description.Description;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.order.Order;
import com.salesmanager.core.model.order.orderproduct.OrderProduct;

/**
 * Order columns that copy values verbatim from catalog / customer columns
 * must be at least as wide as their source, otherwise a valid product name
 * or customer email fails the order insert at checkout.
 */
public class OrderColumnLengthTest {

	@Test
	public void orderProductNameFitsProductDescriptionName() throws Exception {
		Assert.assertTrue("ORDER_PRODUCT.PRODUCT_NAME is narrower than DESCRIPTION.NAME",
				length(OrderProduct.class, "productName") >= length(Description.class, "name"));
	}

	@Test
	public void orderEmailFitsCustomerEmail() throws Exception {
		Assert.assertTrue("ORDERS.CUSTOMER_EMAIL_ADDRESS is narrower than CUSTOMER.CUSTOMER_EMAIL_ADDRESS",
				length(Order.class, "customerEmailAddress") >= length(Customer.class, "emailAddress"));
	}

	private static int length(Class<?> type, String fieldName) throws NoSuchFieldException {
		Field field = type.getDeclaredField(fieldName);
		Column column = field.getAnnotation(Column.class);
		Assert.assertNotNull(type.getSimpleName() + "." + fieldName + " has no @Column", column);
		return column.length();
	}

}
