package com.salesmanager.core.model.order.orderstatus;

public enum OrderStatus {
	
	ORDERED("ordered"),
	PROCESSED("processed"),
	DELIVERED("delivered"),
	REFUNDED("refunded"),
	CANCELED("canceled"),
	/** Order persisted, payment not yet confirmed. */
	PAYMENT_PENDING("payment_pending"),
	/** Payment was attempted and declined or errored; order kept for audit. */
	PAYMENT_FAILED("payment_failed"),
	;
	
	private String value;
	
	private OrderStatus(String value) {
		this.value = value;
	}
	
	public String getValue() {
		return value;
	}
}
