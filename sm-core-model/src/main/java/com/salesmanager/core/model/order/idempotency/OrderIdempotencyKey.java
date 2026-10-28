package com.salesmanager.core.model.order.idempotency;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.UniqueConstraint;

/**
 * Records a checkout Idempotency-Key so a retried checkout returns the
 * original order instead of charging again. One row per (merchant, key).
 */
@Entity
@Table(name = "ORDER_IDEMPOTENCY_KEY", uniqueConstraints = @UniqueConstraint(name = "UK_ORDER_IDEMPOTENCY_KEY", columnNames = {
		"MERCHANT_ID", "IDEMPOTENCY_KEY" }))
public class OrderIdempotencyKey {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ID")
	private Long id;

	@Column(name = "MERCHANT_ID", nullable = false)
	private Integer merchantId;

	@Column(name = "IDEMPOTENCY_KEY", nullable = false, length = 255)
	private String idempotencyKey;

	@Column(name = "CART_CODE", nullable = false, length = 255)
	private String cartCode;

	@Column(name = "REQUEST_HASH", nullable = false, length = 64)
	private String requestHash;

	/** Null while the checkout is in flight. */
	@Column(name = "ORDER_ID")
	private Long orderId;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "DATE_CREATED", nullable = false)
	private Date dateCreated;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Integer getMerchantId() { return merchantId; }
	public void setMerchantId(Integer merchantId) { this.merchantId = merchantId; }
	public String getIdempotencyKey() { return idempotencyKey; }
	public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
	public String getCartCode() { return cartCode; }
	public void setCartCode(String cartCode) { this.cartCode = cartCode; }
	public String getRequestHash() { return requestHash; }
	public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
	public Long getOrderId() { return orderId; }
	public void setOrderId(Long orderId) { this.orderId = orderId; }
	public Date getDateCreated() { return dateCreated; }
	public void setDateCreated(Date dateCreated) { this.dateCreated = dateCreated; }
}
