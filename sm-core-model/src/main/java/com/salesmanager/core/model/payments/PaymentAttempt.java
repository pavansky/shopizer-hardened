package com.salesmanager.core.model.payments;

import java.math.BigDecimal;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.UniqueConstraint;

/**
 * Write-ahead record of one payment gateway call. It is committed before the
 * gateway is called, so a crash between the charge and the payment
 * Transaction still leaves a reference the reconciler can look up at the
 * gateway. The attempt reference is sent to the gateway (Braintree order id,
 * Stripe idempotency key and metadata).
 */
@Entity
@Table(name = "PAYMENT_ATTEMPT",
		uniqueConstraints = @UniqueConstraint(name = "UK_PAYMENT_ATTEMPT_REF", columnNames = "ATTEMPT_REF"),
		indexes = { @Index(name = "IDX_PAYMENT_ATTEMPT_ORDER", columnList = "ORDER_ID"),
				@Index(name = "IDX_PAYMENT_ATTEMPT_STATUS", columnList = "STATUS, DATE_CREATED") })
public class PaymentAttempt {

	/** Key under which the attempt reference is passed to payment modules in Payment.getPaymentMetaData(). */
	public static final String META_ATTEMPT_REF = "attemptRef";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "ID")
	private Long id;

	@Column(name = "ORDER_ID", nullable = false)
	private Long orderId;

	@Column(name = "MERCHANT_ID", nullable = false)
	private Integer merchantId;

	@Column(name = "MODULE_CODE", nullable = false, length = 64)
	private String moduleCode;

	@Column(name = "ATTEMPT_REF", nullable = false, length = 36)
	private String attemptRef;

	/** Known before the call for some gateways (Stripe PaymentIntent id), otherwise the gateway transaction id. */
	@Column(name = "GATEWAY_REF", length = 255)
	private String gatewayRef;

	@Column(name = "AMOUNT", precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(name = "CURRENCY", length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "STATUS", nullable = false, length = 16)
	private PaymentAttemptStatus status;

	@Column(name = "FAILURE_REASON", length = 255)
	private String failureReason;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "DATE_CREATED", nullable = false)
	private Date dateCreated;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "DATE_UPDATED", nullable = false)
	private Date dateUpdated;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getOrderId() { return orderId; }
	public void setOrderId(Long orderId) { this.orderId = orderId; }
	public Integer getMerchantId() { return merchantId; }
	public void setMerchantId(Integer merchantId) { this.merchantId = merchantId; }
	public String getModuleCode() { return moduleCode; }
	public void setModuleCode(String moduleCode) { this.moduleCode = moduleCode; }
	public String getAttemptRef() { return attemptRef; }
	public void setAttemptRef(String attemptRef) { this.attemptRef = attemptRef; }
	public String getGatewayRef() { return gatewayRef; }
	public void setGatewayRef(String gatewayRef) { this.gatewayRef = gatewayRef; }
	public BigDecimal getAmount() { return amount; }
	public void setAmount(BigDecimal amount) { this.amount = amount; }
	public String getCurrency() { return currency; }
	public void setCurrency(String currency) { this.currency = currency; }
	public PaymentAttemptStatus getStatus() { return status; }
	public void setStatus(PaymentAttemptStatus status) { this.status = status; }
	public String getFailureReason() { return failureReason; }
	public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
	public Date getDateCreated() { return dateCreated; }
	public void setDateCreated(Date dateCreated) { this.dateCreated = dateCreated; }
	public Date getDateUpdated() { return dateUpdated; }
	public void setDateUpdated(Date dateUpdated) { this.dateUpdated = dateUpdated; }
}
