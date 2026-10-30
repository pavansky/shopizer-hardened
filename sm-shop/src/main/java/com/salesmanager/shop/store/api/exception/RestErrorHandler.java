package com.salesmanager.shop.store.api.exception;

import java.sql.SQLTransientConnectionException;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice({"com.salesmanager.shop.store.api"})
public class RestErrorHandler {
  
    private static final Logger log = LoggerFactory.getLogger(RestErrorHandler.class);

    /** seconds a client should wait before retrying when no database connection was free */
    static final String POOL_TIMEOUT_RETRY_AFTER_SECONDS = "2";

    @RequestMapping(produces = "application/json")
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEntity> handleServiceException(Exception exception) {
        if (isConnectionPoolTimeout(exception)) {
            return connectionPoolTimeout(exception);
        }
        log.error(exception.getMessage(), exception);
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        ErrorEntity errorEntity = createErrorEntity("500", exception.getMessage(),
        		rootCause.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorEntity);
    }

    /**
     * Hikari throws SQLTransientConnectionException when no pooled connection becomes free
     * within db.connectionTimeout. Services and Spring wrap it (ServiceException,
     * CannotCreateTransactionException, DataAccessResourceFailureException), so the cause
     * chain is searched. The request did no work, so it is safe to retry: answer 503 with
     * Retry-After instead of a 500 or 400 that clients and alerts read as a defect.
     */
    static boolean isConnectionPoolTimeout(Throwable exception) {
        Throwable t = exception;
        for (int depth = 0; t != null && depth < 20; depth++) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return false;
    }

    private ResponseEntity<ErrorEntity> connectionPoolTimeout(Throwable exception) {
        log.warn("No database connection available, answering 503: " + exception.getMessage());
        ErrorEntity errorEntity = createErrorEntity("503", "Service temporarily unavailable, retry shortly", null);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, POOL_TIMEOUT_RETRY_AFTER_SECONDS)
                .body(errorEntity);
    }


    /**
     * Generic exception serviceException handler
     */
    @RequestMapping(produces = "application/json")
    @ExceptionHandler(ServiceRuntimeException.class)
    public ResponseEntity<ErrorEntity> handleServiceException(ServiceRuntimeException exception) {
        if (isConnectionPoolTimeout(exception)) {
            return connectionPoolTimeout(exception);
        }
        log.error(exception.getErrorMessage(), exception);
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode()!=null?exception.getErrorCode():"500", exception.getErrorMessage(),
        		rootCause.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorEntity);
    }

    @RequestMapping(produces = "application/json")
    @ExceptionHandler(ConversionRuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public @ResponseBody ErrorEntity handleServiceException(ConversionRuntimeException exception) {
        log.error(exception.getErrorMessage(), exception);
        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode(), exception.getErrorMessage(),
            exception.getLocalizedMessage());
        return errorEntity;
    }

    @RequestMapping(produces = "application/json")
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public @ResponseBody ErrorEntity handleServiceException(ResourceNotFoundException exception) {
        log.error(exception.getErrorMessage(), exception);

        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode(), exception.getErrorMessage(),
                exception.getLocalizedMessage());
        return errorEntity;
    }
    
    @RequestMapping(produces = "application/json")
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public @ResponseBody ErrorEntity handleServiceException(UnauthorizedException exception) {
        log.error(exception.getErrorMessage(), exception);

        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode(), exception.getErrorMessage(),
                exception.getLocalizedMessage());
        return errorEntity;
    }

    @RequestMapping(produces = "application/json")
    @ExceptionHandler(RestApiException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public @ResponseBody ErrorEntity handleRestApiException(RestApiException exception) {
        log.error(exception.getErrorMessage(), exception);

        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode(), exception.getErrorMessage(),
                exception.getLocalizedMessage());
        return errorEntity;
    }

    @RequestMapping(produces = "application/json")
    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public @ResponseBody ErrorEntity handleConflictException(ConflictException exception) {
        log.warn(exception.getErrorMessage());

        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode(), exception.getErrorMessage(),
                exception.getLocalizedMessage());
        return errorEntity;
    }

    @RequestMapping(produces = "application/json")
    @ExceptionHandler(NotImplementedException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public @ResponseBody ErrorEntity handleNotImplementedException(NotImplementedException exception) {
        log.warn(exception.getErrorMessage());

        ErrorEntity errorEntity = createErrorEntity(exception.getErrorCode(), exception.getErrorMessage(),
                exception.getLocalizedMessage());
        return errorEntity;
    }

    private ErrorEntity createErrorEntity(String errorCode, String message, String detailMessage) {
        ErrorEntity errorEntity = new ErrorEntity();
        Optional.ofNullable(errorCode)
                .ifPresent(errorEntity::setErrorCode);

        String resultMessage = (message != null && detailMessage !=null)  ? new StringBuilder().append(message).append(", ").append(detailMessage).toString() : detailMessage;
        if(StringUtils.isBlank(resultMessage)) {
        	resultMessage = message;
        }
        Optional.ofNullable(resultMessage)
                .ifPresent(errorEntity::setMessage);
        return errorEntity;
    }
}
