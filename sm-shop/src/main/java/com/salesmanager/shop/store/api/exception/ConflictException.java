package com.salesmanager.shop.store.api.exception;

/**
 * Thrown when a request is valid but conflicts with the current state of the
 * resource (for example capturing an order that has nothing left to capture).
 * Mapped to HTTP 409 by RestErrorHandler.
 */
public class ConflictException extends RestApiException {

  private final static String ERROR_CODE = "409";

  private static final long serialVersionUID = 1L;

  public ConflictException(String message) {
    super(ERROR_CODE, message);
  }

}
