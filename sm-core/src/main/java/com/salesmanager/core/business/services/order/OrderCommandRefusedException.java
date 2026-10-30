package com.salesmanager.core.business.services.order;

/**
 * An order command (cancel, re-check) cannot run in the order's current state,
 * for example cancelling an order that is already cancelled. Nothing was
 * changed; the API answers 409 with the message.
 */
public class OrderCommandRefusedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public OrderCommandRefusedException(String message) {
		super(message);
	}
}
