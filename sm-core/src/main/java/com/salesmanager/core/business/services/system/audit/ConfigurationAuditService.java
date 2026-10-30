package com.salesmanager.core.business.services.system.audit;

import java.util.Collection;
import java.util.List;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.system.audit.ConfigurationAuditEntry;

/**
 * Append-only trail of configuration changes. There is deliberately no update or
 * delete operation.
 */
public interface ConfigurationAuditService {

	/**
	 * Records that the actor changed the named settings of a module. Joins the caller's
	 * transaction, so the row commits or rolls back together with the change itself.
	 */
	ConfigurationAuditEntry record(MerchantStore store, String configType, String moduleCode, String actor,
			Collection<String> changedKeys);

	/** Entries of the store, newest first. */
	List<ConfigurationAuditEntry> listByStore(MerchantStore store, int max);
}
