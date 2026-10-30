package com.salesmanager.core.business.services.tax;

import java.util.List;

import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.common.generic.SalesManagerEntityService;
import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.tax.taxclass.TaxClass;

public interface TaxClassService extends SalesManagerEntityService<Long, TaxClass> {

	List<TaxClass> listByStore(MerchantStore store) throws ServiceException;

	/**
	 * @deprecated not store scoped and fails once two stores share a code;
	 * use {@link #getByCode(String, MerchantStore)}
	 */
	@Deprecated
	TaxClass getByCode(String code) throws ServiceException;

	TaxClass getByCode(String code, MerchantStore store)
			throws ServiceException;
	
	boolean exists(String code, MerchantStore store) throws ServiceException;
	
	TaxClass saveOrUpdate(TaxClass taxClass) throws ServiceException;
	

}
