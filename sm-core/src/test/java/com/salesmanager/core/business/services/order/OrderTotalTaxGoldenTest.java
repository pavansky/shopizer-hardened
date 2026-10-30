package com.salesmanager.core.business.services.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesmanager.core.business.repositories.order.OrderRepository;
import com.salesmanager.core.business.services.order.ordertotal.OrderTotalService;
import com.salesmanager.core.business.services.shipping.ShippingService;
import com.salesmanager.core.business.services.system.MerchantConfigurationService;
import com.salesmanager.core.business.services.tax.TaxClassService;
import com.salesmanager.core.business.services.tax.TaxRateService;
import com.salesmanager.core.business.services.tax.TaxServiceImpl;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.order.OrderTotal;
import com.salesmanager.core.model.order.OrderTotalSummary;
import com.salesmanager.core.model.order.OrderTotalType;
import com.salesmanager.core.model.order.RebatesOrderTotalVariation;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.reference.zone.Zone;
import com.salesmanager.core.model.shipping.ShippingConfiguration;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.tax.taxclass.TaxClass;
import com.salesmanager.core.model.tax.taxrate.TaxRate;
import com.salesmanager.core.model.tax.taxrate.TaxRateDescription;

/**
 * Golden order totals: a coupon, shipping and two tax classes, through
 * OrderServiceImpl.caculateOrderTotal with the real TaxServiceImpl.
 *
 * Cart: 1 x 100.00 (standard 10%) and 2 x 30.00 (reduced 5%), coupon 10%
 * (16.00, computed per line by the promo module), shipping 15.00, store taxes
 * shipping.
 */
class OrderTotalTaxGoldenTest {

	private final OrderServiceImpl orderService = new OrderServiceImpl(mock(OrderRepository.class));
	private final TaxServiceImpl taxService = new TaxServiceImpl();
	private final OrderTotalService orderTotalService = mock(OrderTotalService.class);
	private final ShippingService shippingService = mock(ShippingService.class);
	private final TaxRateService taxRateService = mock(TaxRateService.class);
	private final TaxClassService taxClassService = mock(TaxClassService.class);

	private final Country canada = new Country("CA");
	private final Zone quebec = new Zone();
	private final MerchantStore store = new MerchantStore();
	private final Language english = new Language("en");
	private final TaxClass standard = new TaxClass(TaxClass.DEFAULT_TAX_CLASS);
	private final TaxClass reduced = new TaxClass("REDUCED");
	private final ShippingConfiguration shippingConfiguration = new ShippingConfiguration();
	private final List<TaxRate> rates = new ArrayList<TaxRate>();

	@BeforeEach
	void setUp() throws Exception {
		inject(OrderServiceImpl.class, orderService, "taxService", taxService);
		inject(OrderServiceImpl.class, orderService, "orderTotalService", orderTotalService);
		inject(OrderServiceImpl.class, orderService, "shippingService", shippingService);
		inject(TaxServiceImpl.class, taxService, "merchantConfigurationService", mock(MerchantConfigurationService.class));
		inject(TaxServiceImpl.class, taxService, "taxRateService", taxRateService);
		inject(TaxServiceImpl.class, taxService, "taxClassService", taxClassService);
		inject(TaxServiceImpl.class, taxService, "shippingService", shippingService);

		canada.setId(1);
		quebec.setId(10L);
		quebec.setCode("QC");
		store.setId(1);
		store.setCountry(canada);
		store.setZone(quebec);
		standard.setId(1L);
		reduced.setId(2L);
		shippingConfiguration.setTaxOnShipping(true);

		rates.add(rate("STD", "10", standard));
		rates.add(rate("RED", "5", reduced));

		when(shippingService.getShippingConfiguration(store)).thenReturn(shippingConfiguration);
		when(taxClassService.getByCode(TaxClass.DEFAULT_TAX_CLASS)).thenReturn(standard);
		when(taxClassService.getByCode(TaxClass.DEFAULT_TAX_CLASS, store)).thenReturn(standard);
		when(taxRateService.listByCountryZoneAndTaxClass(any(), any(), any(), eq(store), any())).thenAnswer(inv -> {
			TaxClass taxClass = inv.getArgument(2);
			return rates.stream().filter(r -> r.getTaxClass().getId().equals(taxClass.getId()))
					.collect(Collectors.toList());
		});
	}

	@Test
	void couponReducesTheTaxBaseAndShippingIsTaxedAtTheDefaultClass() throws Exception {
		coupon("10.00", "6.00");

		OrderTotalSummary totals = orderService.caculateOrderTotal(summary(false), customer(), store, english);

		// standard base 100.00 - 10.00 + 15.00 shipping = 105.00 -> 10.50
		// reduced base 60.00 - 6.00 = 54.00 -> 2.70
		assertEquals(new BigDecimal("144.00"), totals.getSubTotal());
		assertEquals(new BigDecimal("13.20"), totals.getTaxTotal());
		assertEquals(new BigDecimal("172.20"), totals.getTotal());
		assertEquals(new BigDecimal("10.50"), taxLine(totals, "STD"));
		assertEquals(new BigDecimal("2.70"), taxLine(totals, "RED"));
	}

	@Test
	void freeShippingWithCouponTaxesOnlyTheDiscountedItems() throws Exception {
		coupon("10.00", "6.00");

		OrderTotalSummary totals = orderService.caculateOrderTotal(summary(true), customer(), store, english);

		// 90.00 x 10% = 9.00 and 54.00 x 5% = 2.70
		assertEquals(new BigDecimal("11.70"), totals.getTaxTotal());
		assertEquals(new BigDecimal("155.70"), totals.getTotal());
	}

	@Test
	void unroundedCouponIsRoundedBeforeItReachesTheTotals() throws Exception {
		// a 10% coupon computed from a double, as PromoCodeCalculatorModule does
		BigDecimal tenPercent = new BigDecimal(0.1);
		coupon(new BigDecimal("100.00").multiply(tenPercent).toPlainString(),
				new BigDecimal("30.00").multiply(tenPercent).multiply(new BigDecimal(2)).toPlainString());

		OrderTotalSummary totals = orderService.caculateOrderTotal(summary(true), customer(), store, english);

		assertEquals(new BigDecimal("144.00"), totals.getSubTotal());
		assertEquals(new BigDecimal("155.70"), totals.getTotal());
	}

	private void coupon(String... perLine) throws Exception {
		List<OrderTotal> variations = new ArrayList<OrderTotal>();
		for (String amount : perLine) {
			OrderTotal discount = new OrderTotal();
			discount.setOrderTotalType(OrderTotalType.SUBTOTAL);
			discount.setOrderTotalCode("order.total.discount");
			discount.setValue(new BigDecimal(amount));
			variations.add(discount);
		}
		RebatesOrderTotalVariation variation = new RebatesOrderTotalVariation();
		variation.setVariations(variations);
		when(orderTotalService.findOrderTotalVariation(any(), any(), eq(store), any())).thenReturn(variation);
	}

	private static BigDecimal taxLine(OrderTotalSummary totals, String label) {
		return totals.getTotals().stream()
				.filter(t -> t.getOrderTotalType() == OrderTotalType.TAX && label.equals(t.getText()))
				.map(OrderTotal::getValue).findFirst().orElse(null);
	}

	private OrderSummary summary(boolean freeShipping) {
		OrderSummary summary = new OrderSummary();
		summary.setProducts(new ArrayList<ShoppingCartItem>(Arrays.asList(line("100.00", 1, null), line("30.00", 2, reduced))));
		ShippingSummary shipping = new ShippingSummary();
		shipping.setShipping(new BigDecimal("15.00"));
		shipping.setFreeShipping(freeShipping);
		summary.setShippingSummary(shipping);
		return summary;
	}

	private Customer customer() {
		Customer customer = new Customer();
		Billing billing = new Billing();
		billing.setCountry(canada);
		billing.setZone(quebec);
		customer.setBilling(billing);
		return customer;
	}

	private static ShoppingCartItem line(String price, int quantity, TaxClass taxClass) {
		Product product = new Product();
		product.setTaxClass(taxClass);
		ShoppingCartItem item = new ShoppingCartItem();
		item.setProduct(product);
		item.setItemPrice(new BigDecimal(price));
		item.setQuantity(quantity);
		return item;
	}

	private TaxRate rate(String code, String percent, TaxClass taxClass) {
		TaxRate rate = new TaxRate();
		rate.setCode(code);
		rate.setTaxRate(new BigDecimal(percent));
		rate.setZone(quebec);
		rate.setCountry(canada);
		rate.setTaxClass(taxClass);
		TaxRateDescription description = new TaxRateDescription();
		description.setName(code);
		description.setLanguage(english);
		rate.getDescriptions().add(description);
		return rate;
	}

	private static void inject(Class<?> type, Object target, String name, Object value) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
