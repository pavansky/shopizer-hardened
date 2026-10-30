package com.salesmanager.core.business.services.tax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.shipping.ShippingService;
import com.salesmanager.core.business.services.system.MerchantConfigurationService;
import com.salesmanager.core.model.common.Billing;
import com.salesmanager.core.model.common.Delivery;
import com.salesmanager.core.model.customer.Customer;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.order.OrderSummary;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.reference.zone.Zone;
import com.salesmanager.core.model.shipping.ShippingConfiguration;
import com.salesmanager.core.model.shipping.ShippingSummary;
import com.salesmanager.core.model.shoppingcart.ShoppingCartItem;
import com.salesmanager.core.model.system.MerchantConfiguration;
import com.salesmanager.core.model.tax.TaxBasisCalculation;
import com.salesmanager.core.model.tax.TaxConfiguration;
import com.salesmanager.core.model.tax.TaxItem;
import com.salesmanager.core.model.tax.taxclass.TaxClass;
import com.salesmanager.core.model.tax.taxrate.TaxRate;
import com.salesmanager.core.model.tax.taxrate.TaxRateDescription;

@Service("taxService")
public class TaxServiceImpl 
		implements TaxService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TaxServiceImpl.class);

	private final static String TAX_CONFIGURATION = "TAX_CONFIG";
	
	@Inject
	private MerchantConfigurationService merchantConfigurationService;
	
	@Inject
	private TaxRateService taxRateService;
	
	@Inject
	private TaxClassService taxClassService;
	
	@Inject
	private ShippingService shippingService;
	
	@Override
	public TaxConfiguration getTaxConfiguration(MerchantStore store) throws ServiceException {
		
		
		
		MerchantConfiguration configuration = merchantConfigurationService.getMerchantConfiguration(TAX_CONFIGURATION, store);
		TaxConfiguration taxConfiguration = null;
		if(configuration!=null) {
			String value = configuration.getValue();
			
			ObjectMapper mapper = new ObjectMapper();
			try {
				taxConfiguration = mapper.readValue(value, TaxConfiguration.class);
			} catch(Exception e) {
				throw new ServiceException("Cannot parse json string " + value);
			}
		}
		return taxConfiguration;
	}
	
	
	@Override
	public void saveTaxConfiguration(TaxConfiguration shippingConfiguration, MerchantStore store) throws ServiceException {
		
		MerchantConfiguration configuration = merchantConfigurationService.getMerchantConfiguration(TAX_CONFIGURATION, store);

		if(configuration==null) {
			configuration = new MerchantConfiguration();
			configuration.setMerchantStore(store);
			configuration.setKey(TAX_CONFIGURATION);
		}
		
		String value = shippingConfiguration.toJSONString();
		configuration.setValue(value);
		merchantConfigurationService.saveOrUpdate(configuration);
		
	}
	
	@Override
	public List<TaxItem> calculateTax(OrderSummary orderSummary, Customer customer, MerchantStore store, Language language) throws ServiceException {
		return calculateTax(orderSummary, customer, store, language, BigDecimal.ZERO);
	}

	@Override
	public List<TaxItem> calculateTax(OrderSummary orderSummary, Customer customer, MerchantStore store, Language language, BigDecimal orderDiscount) throws ServiceException {

		if(customer==null) {
			return null;
		}

		List<ShoppingCartItem> items = orderSummary.getProducts();
		if(items==null) {
			return new ArrayList<TaxItem>();
		}

		//determine tax calculation basis
		TaxConfiguration taxConfiguration = this.getTaxConfiguration(store);
		if(taxConfiguration==null) {
			taxConfiguration = new TaxConfiguration();
		}
		TaxBasisCalculation taxBasisCalculation = taxConfiguration.getTaxBasisCalculation();
		if(taxBasisCalculation==null) {
			taxBasisCalculation = TaxBasisCalculation.SHIPPINGADDRESS;
		}

		Country country = null;
		Zone zone = null;
		String stateProvince = null;

		Billing billing = customer.getBilling();
		if(billing!=null) {
			country = billing.getCountry();
			zone = billing.getZone();
			stateProvince = billing.getState();
		}

		switch(taxBasisCalculation) {
			case SHIPPINGADDRESS:
				//falls back to the billing address when there is no delivery address
				Delivery shipping = customer.getDelivery();
				if(shipping!=null && shipping.getCountry()!=null) {
					country = shipping.getCountry();
					zone = shipping.getZone();
					stateProvince = shipping.getState();
				}
				break;
			case STOREADDRESS:
				country = store.getCountry();
				zone = store.getZone();
				stateProvince = store.getStorestateprovince();
				break;
			case BILLINGADDRESS:
			default:
				break;
		}

		//check other conditions
		//do not collect tax on other provinces of same country
		if(!taxConfiguration.isCollectTaxIfDifferentProvinceOfStoreCountry()) {
			if((zone!=null && store.getZone()!=null) && (zone.getId().longValue() != store.getZone().getId().longValue())) {
				return null;
			}
			if(!StringUtils.isBlank(stateProvince)) {
				if(store.getZone()!=null) {
					if(!store.getZone().getName().equals(stateProvince)) {
						return null;
					}
				}
				else if(!StringUtils.isBlank(store.getStorestateprovince())) {

					if(!store.getStorestateprovince().equals(stateProvince)) {
						return null;
					}
				}
			}
		}

		//collect tax in different countries
		if(taxConfiguration.isCollectTaxIfDifferentCountryOfStoreCountry()) {
			//use store country
			country = store.getCountry();
			zone = store.getZone();
			stateProvince = store.getStorestateprovince();
		}

		if(country == null || (zone == null && StringUtils.isBlank(stateProvince))) {
			return null;
		}

		//products without a tax class use the store's DEFAULT class, as does shipping
		TaxClass defaultTaxClass = null;
		boolean defaultLookedUp = false;

		List<BigDecimal> lineAmounts = new ArrayList<BigDecimal>();
		List<TaxClass> lineClasses = new ArrayList<TaxClass>();
		for(ShoppingCartItem item : items) {
			TaxClass taxClass = item.getProduct().getTaxClass();
			if(taxClass==null) {
				if(!defaultLookedUp) {
					defaultTaxClass = defaultTaxClass(store);
					defaultLookedUp = true;
				}
				taxClass = defaultTaxClass;
			}
			lineClasses.add(taxClass);
			lineAmounts.add(item.getItemPrice().multiply(new BigDecimal(item.getQuantity())));
		}
		List<BigDecimal> discountedAmounts = TaxCalculator.allocateDiscount(lineAmounts, orderDiscount);

		//tax base by tax class, in cart order
		Map<Long,BigDecimal> taxClassAmountMap = new LinkedHashMap<Long,BigDecimal>();
		Map<Long,TaxClass> taxClasses = new HashMap<Long,TaxClass>();
		for(int i = 0; i < lineClasses.size(); i++) {
			TaxClass taxClass = lineClasses.get(i);
			if(taxClass==null) {
				//store without a DEFAULT class: no rate can apply to this line
				continue;
			}
			taxClassAmountMap.merge(taxClass.getId(), discountedAmounts.get(i), BigDecimal::add);
			taxClasses.put(taxClass.getId(), taxClass);
		}

		//shipping is taxed at the DEFAULT class, only when the store taxes shipping
		BigDecimal taxableShipping = taxableShipping(orderSummary.getShippingSummary(), store);
		if(taxableShipping.signum() > 0 && !defaultLookedUp) {
			defaultTaxClass = defaultTaxClass(store);
		}
		if(taxableShipping.signum() > 0 && defaultTaxClass!=null) {
			taxClassAmountMap.merge(defaultTaxClass.getId(), taxableShipping, BigDecimal::add);
			taxClasses.put(defaultTaxClass.getId(), defaultTaxClass);
		}

		//one tax line per rate code, rates of a class only apply to that class
		Map<String,TaxItem> taxItemsMap = new LinkedHashMap<String,TaxItem>();
		for(Map.Entry<Long,BigDecimal> entry : taxClassAmountMap.entrySet()) {

			TaxClass taxClass = taxClasses.get(entry.getKey());
			List<TaxRate> taxRates = null;
			if(!StringUtils.isBlank(stateProvince)&& zone==null) {
				taxRates = taxRateService.listByCountryStateProvinceAndTaxClass(country, stateProvince, taxClass, store, language);
			} else {
				taxRates = taxRateService.listByCountryZoneAndTaxClass(country, zone, taxClass, store, language);
			}

			if(taxRates==null || taxRates.isEmpty()){
				continue;
			}

			List<BigDecimal> amounts = TaxCalculator.applyRates(entry.getValue(), taxRates);
			for(int i = 0; i < taxRates.size(); i++) {
				TaxRate taxRate = taxRates.get(i);
				TaxItem taxItem = taxItemsMap.get(taxRate.getCode());
				if(taxItem==null) {
					taxItem = new TaxItem();
					taxItem.setItemPrice(amounts.get(i));
					taxItem.setLabel(label(taxRate, language));
					taxItem.setTaxRate(taxRate);
					taxItemsMap.put(taxRate.getCode(), taxItem);
				} else {
					taxItem.setItemPrice(taxItem.getItemPrice().add(amounts.get(i)));
				}
			}
		}

		if(taxItemsMap.isEmpty()) {
			return null;
		}

		//receipt order: by tax priority, so a compound tax follows the tax it compounds
		List<TaxItem> taxItems = new ArrayList<TaxItem>(taxItemsMap.values());
		taxItems.sort(Comparator.comparingInt((TaxItem t) -> priority(t.getTaxRate())));
		return taxItems;

	}

	/**
	 * The store's DEFAULT tax class. A store created without one (the store
	 * API does not create it) falls back to its only tax class, if it has
	 * exactly one; otherwise unclassified lines and shipping are not taxed.
	 * Both cases are logged, since they usually mean a setup problem.
	 */
	private TaxClass defaultTaxClass(MerchantStore store) throws ServiceException {
		TaxClass defaultTaxClass = taxClassService.getByCode(TaxClass.DEFAULT_TAX_CLASS, store);
		if(defaultTaxClass!=null) {
			return defaultTaxClass;
		}
		List<TaxClass> storeClasses = taxClassService.listByStore(store);
		if(storeClasses!=null && storeClasses.size()==1) {
			TaxClass only = storeClasses.get(0);
			LOGGER.warn("Store {} has no {} tax class, products without a tax class are taxed at its only tax class {}",
					store.getCode(), TaxClass.DEFAULT_TAX_CLASS, only.getCode());
			return only;
		}
		if(storeClasses!=null && !storeClasses.isEmpty()) {
			LOGGER.warn("Store {} has no {} tax class and {} other tax classes, products without a tax class and shipping are not taxed",
					store.getCode(), TaxClass.DEFAULT_TAX_CLASS, storeClasses.size());
		}
		return null;
	}

	private static int priority(TaxRate taxRate) {
		return taxRate!=null && taxRate.getTaxPriority()!=null ? taxRate.getTaxPriority() : 0;
	}

	/**
	 * Shipping and handling as charged by OrderServiceImpl, when the store's
	 * shipping configuration says shipping is taxable. Free shipping is not
	 * taxed; handling is charged, and taxed, only when the store has handling fees.
	 */
	private BigDecimal taxableShipping(ShippingSummary shippingSummary, MerchantStore store) throws ServiceException {
		if(shippingSummary==null) {
			return BigDecimal.ZERO;
		}
		ShippingConfiguration shippingConfiguration = shippingService.getShippingConfiguration(store);
		if(shippingConfiguration==null || !shippingConfiguration.isTaxOnShipping()) {
			return BigDecimal.ZERO;
		}
		BigDecimal amount = BigDecimal.ZERO;
		if(!shippingSummary.isFreeShipping() && shippingSummary.getShipping()!=null && shippingSummary.getShipping().signum() > 0) {
			amount = amount.add(shippingSummary.getShipping());
		}
		BigDecimal handlingFees = shippingConfiguration.getHandlingFees();
		if(shippingSummary.getHandling()!=null && shippingSummary.getHandling().signum() > 0
				&& handlingFees!=null && handlingFees.signum() > 0) {
			amount = amount.add(shippingSummary.getHandling());
		}
		return amount;
	}

	private static String label(TaxRate taxRate, Language language) {
		TaxRateDescription first = null;
		for(TaxRateDescription description : taxRate.getDescriptions()) {
			if(first==null) {
				first = description;
			}
			if(language!=null && description.getLanguage()!=null
					&& language.getCode()!=null && language.getCode().equals(description.getLanguage().getCode())) {
				return description.getName();
			}
		}
		return first!=null ? first.getName() : taxRate.getCode();
	}

}
