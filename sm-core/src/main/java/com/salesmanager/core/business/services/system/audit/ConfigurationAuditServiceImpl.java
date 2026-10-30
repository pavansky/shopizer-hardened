package com.salesmanager.core.business.services.system.audit;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salesmanager.core.model.merchant.MerchantStore;
import com.salesmanager.core.model.system.audit.ConfigurationAuditEntry;

@Service("configurationAuditService")
public class ConfigurationAuditServiceImpl implements ConfigurationAuditService {

	@PersistenceContext
	private EntityManager em;

	@Override
	@Transactional
	public ConfigurationAuditEntry record(MerchantStore store, String configType, String moduleCode, String actor,
			Collection<String> changedKeys) {
		Validate.notNull(store, "store is required");
		Validate.notNull(store.getId(), "store id is required");
		Validate.notBlank(configType, "configType is required");
		Validate.notBlank(moduleCode, "moduleCode is required");
		Validate.notBlank(actor, "actor is required");

		ConfigurationAuditEntry entry = new ConfigurationAuditEntry();
		entry.setMerchantId(store.getId());
		entry.setConfigType(configType);
		entry.setModuleCode(moduleCode);
		entry.setActor(actor);
		entry.setChangedKeys(changedKeys == null ? null
				: StringUtils.abbreviate(String.join(",", changedKeys), ConfigurationAuditEntry.CHANGED_KEYS_LENGTH));
		entry.setDateCreated(new Date());
		em.persist(entry);
		return entry;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ConfigurationAuditEntry> listByStore(MerchantStore store, int max) {
		return em.createQuery("select a from ConfigurationAuditEntry a where a.merchantId = :m order by a.id desc",
				ConfigurationAuditEntry.class).setParameter("m", store.getId()).setMaxResults(max).getResultList();
	}
}
