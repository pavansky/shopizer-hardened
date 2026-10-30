package com.salesmanager.core.model.system;

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
 * One mutating request to the admin API: who, which store, what, and how it
 * ended. Request and response bodies are never stored. Rows are written once
 * and never updated (Hibernate ignores changes to an immutable entity and the
 * repository offers no update or delete).
 */
@Entity
@Immutable
@Table(name = "ADMIN_AUDIT", indexes = {
		@Index(name = "IDX_ADMIN_AUDIT_TIME", columnList = "OCCURRED_AT"),
		@Index(name = "IDX_ADMIN_AUDIT_USER", columnList = "USERNAME, OCCURRED_AT") })
public class AdminAuditEvent {

	public static final int PATH_LENGTH = 512;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ID")
	private Long id;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "OCCURRED_AT", nullable = false)
	private Date occurredAt;

	@Column(name = "USERNAME", length = 100)
	private String username;

	@Column(name = "STORE_CODE", length = 100)
	private String storeCode;

	@Column(name = "HTTP_METHOD", nullable = false, length = 10)
	private String method;

	@Column(name = "PATH", nullable = false, length = PATH_LENGTH)
	private String path;

	@Column(name = "STATUS", nullable = false)
	private int status;

	@Column(name = "CLIENT_IP", length = 64)
	private String clientIp;

	protected AdminAuditEvent() {
	}

	public AdminAuditEvent(Date occurredAt, String username, String storeCode, String method, String path, int status,
			String clientIp) {
		this.occurredAt = new Date(occurredAt.getTime());
		this.username = truncate(username, 100);
		this.storeCode = truncate(storeCode, 100);
		this.method = truncate(method, 10);
		this.path = truncate(path, PATH_LENGTH);
		this.status = status;
		this.clientIp = truncate(clientIp, 64);
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	public Long getId() {
		return id;
	}

	public Date getOccurredAt() {
		return new Date(occurredAt.getTime());
	}

	public String getUsername() {
		return username;
	}

	public String getStoreCode() {
		return storeCode;
	}

	public String getMethod() {
		return method;
	}

	public String getPath() {
		return path;
	}

	public int getStatus() {
		return status;
	}

	public String getClientIp() {
		return clientIp;
	}
}
