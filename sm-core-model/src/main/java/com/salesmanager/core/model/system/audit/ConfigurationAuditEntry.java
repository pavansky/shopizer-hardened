package com.salesmanager.core.model.system.audit;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.hibernate.annotations.Immutable;

/**
 * One change to a store's module configuration (for example payment gateway
 * credentials). Rows are append-only: the entity is immutable and nothing in the
 * application updates or deletes them. Only the names of the settings that changed
 * are kept, never their values.
 */
@Entity
@Immutable
@Table(name = "CONFIGURATION_AUDIT",
		indexes = @Index(name = "IDX_CONFIGURATION_AUDIT_STORE", columnList = "MERCHANT_ID, DATE_CREATED"))
public class ConfigurationAuditEntry {

	public static final String TYPE_PAYMENT = "PAYMENT";
	public static final int CHANGED_KEYS_LENGTH = 2000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ID")
	private Long id;

	@Column(name = "MERCHANT_ID", nullable = false, updatable = false)
	private Integer merchantId;

	@Column(name = "CONFIG_TYPE", nullable = false, updatable = false, length = 32)
	private String configType;

	@Column(name = "MODULE_CODE", nullable = false, updatable = false, length = 64)
	private String moduleCode;

	@Column(name = "ACTOR", nullable = false, updatable = false, length = 255)
	private String actor;

	/** Comma separated names of the settings that changed. */
	@Column(name = "CHANGED_KEYS", updatable = false, length = CHANGED_KEYS_LENGTH)
	private String changedKeys;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "DATE_CREATED", nullable = false, updatable = false)
	private Date dateCreated;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Integer getMerchantId() { return merchantId; }
	public void setMerchantId(Integer merchantId) { this.merchantId = merchantId; }
	public String getConfigType() { return configType; }
	public void setConfigType(String configType) { this.configType = configType; }
	public String getModuleCode() { return moduleCode; }
	public void setModuleCode(String moduleCode) { this.moduleCode = moduleCode; }
	public String getActor() { return actor; }
	public void setActor(String actor) { this.actor = actor; }
	public String getChangedKeys() { return changedKeys; }
	public void setChangedKeys(String changedKeys) { this.changedKeys = changedKeys; }
	public Date getDateCreated() { return dateCreated; }
	public void setDateCreated(Date dateCreated) { this.dateCreated = dateCreated; }
}
