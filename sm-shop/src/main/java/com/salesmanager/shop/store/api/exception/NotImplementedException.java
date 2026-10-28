package com.salesmanager.shop.store.api.exception;

/**
 * Thrown by REST endpoints whose operation is not implemented yet.
 * Mapped to HTTP 501 by RestErrorHandler.
 */
public class NotImplementedException extends RestApiException {

  private final static String ERROR_CODE = "501";

  /**
   * 
   */
  private static final long serialVersionUID = 1L;

  public NotImplementedException(String message) {
    super(ERROR_CODE, message);
  }

}
