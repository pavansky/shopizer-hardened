package com.salesmanager.shop.model.order;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What an admin can do next with an order: the legal next statuses from the
 * transition table, the subset a plain status update accepts, whether it can
 * be cancelled and how much is still refundable. Returned by the transitions,
 * cancel and payment re-check endpoints; message describes what a command did.
 */
public class ReadableOrderLifecycle implements Serializable {

	private static final long serialVersionUID = 1L;

	private Long orderId;
	private String status;
	private List<String> next = new ArrayList<>();
	private List<String> statusUpdates = new ArrayList<>();
	private boolean cancelable;
	private boolean paymentRecheck;
	private boolean paymentConfirm;
	private BigDecimal refundable = BigDecimal.ZERO;
	private String currency;
	private String message;

	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	/** Every status the transition table allows from the current one. */
	public List<String> getNext() {
		return next;
	}

	public void setNext(List<String> next) {
		this.next = next;
	}

	/** The statuses PUT /private/orders/{id}/status accepts from the current one. */
	public List<String> getStatusUpdates() {
		return statusUpdates;
	}

	public void setStatusUpdates(List<String> statusUpdates) {
		this.statusUpdates = statusUpdates;
	}

	public boolean isCancelable() {
		return cancelable;
	}

	public void setCancelable(boolean cancelable) {
		this.cancelable = cancelable;
	}

	/** True for PAYMENT_PENDING and PAYMENT_REVIEW, where a payment re-check can settle the order. */
	public boolean isPaymentRecheck() {
		return paymentRecheck;
	}

	public void setPaymentRecheck(boolean paymentRecheck) {
		this.paymentRecheck = paymentRecheck;
	}

	/** Captured minus already refunded. */
	public BigDecimal getRefundable() {
		return refundable;
	}

	public void setRefundable(BigDecimal refundable) {
		this.refundable = refundable;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	/** True for PAYMENT_REVIEW: a person may confirm the payment by hand (POST /payment/confirm). */
	public boolean isPaymentConfirm() {
		return paymentConfirm;
	}

	public void setPaymentConfirm(boolean paymentConfirm) {
		this.paymentConfirm = paymentConfirm;
	}
}
