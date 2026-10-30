package com.salesmanager.core.business.services.tax;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.springframework.stereotype.Service;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.repositories.tax.TaxRateRepository;
import com.salesmanager.core.business.services.common.generic.SalesManagerEntityServiceImpl;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.reference.country.Country;
import com.salesmanager.core.model.reference.language.Language;
import com.salesmanager.core.model.reference.zone.Zone;
import com.salesmanager.core.model.tax.taxclass.TaxClass;
import com.salesmanager.core.model.tax.taxrate.TaxRate;

@Service("taxRateService")
public class TaxRateServiceImpl extends SalesManagerEntityServiceImpl<Long, TaxRate>
		implements TaxRateService {

	private TaxRateRepository taxRateRepository;
	
	@Inject
	public TaxRateServiceImpl(TaxRateRepository taxRateRepository) {
		super(taxRateRepository);
		this.taxRateRepository = taxRateRepository;
	}

	@Override
	public List<TaxRate> listByStore(MerchantStore store)
			throws ServiceException {
		return taxRateRepository.findByStore(store.getId());
	}
	
	@Override
	public List<TaxRate> listByStore(MerchantStore store, Language language)
			throws ServiceException {
		return taxRateRepository.findByStoreAndLanguage(store.getId(), language.getId());
	}
	
	
	@Override
	public TaxRate getByCode(String code, MerchantStore store)
			throws ServiceException {
		return taxRateRepository.findByStoreAndCode(store.getId(), code);
	}
	
	/**
	 * The language is not a filter: a rate applies whatever languages its
	 * descriptions exist in.
	 */
	@Override
	public List<TaxRate> listByCountryZoneAndTaxClass(Country country, Zone zone, TaxClass taxClass, MerchantStore store, Language language) throws ServiceException {
		if (taxClass == null || taxClass.getId() == null) {
			return Collections.emptyList();
		}
		return taxRateRepository.findByMerchantAndZoneAndCountryAndTaxClass(store.getId(), zone.getId(), country.getId(), taxClass.getId());
	}
	
	@Override
	public List<TaxRate> listByCountryStateProvinceAndTaxClass(Country country, String stateProvince, TaxClass taxClass, MerchantStore store, Language language) throws ServiceException {
		if (taxClass == null || taxClass.getId() == null) {
			return Collections.emptyList();
		}
		return taxRateRepository.findByMerchantAndProvinceAndCountryAndTaxClass(store.getId(), stateProvince, country.getId(), taxClass.getId());
	}
	
	@Override
	public void delete(TaxRate taxRate) throws ServiceException {
		
		taxRateRepository.delete(taxRate);
		
	}
	
	@Override
	public TaxRate saveOrUpdate(TaxRate taxRate) throws ServiceException {
		if(taxRate.getId()!=null && taxRate.getId() > 0) {
			this.update(taxRate);
		} else {
			taxRate = super.saveAndFlush(taxRate);
		}
		return taxRate;
	}

	@Override
	public TaxRate getById(Long id, MerchantStore store) throws ServiceException {
		return taxRateRepository.findByStoreAndId(store.getId(), id);
	}
		

	
}
