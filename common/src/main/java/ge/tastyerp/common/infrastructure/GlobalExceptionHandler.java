package ge.tastyerp.common.infrastructure;

import ge.tastyerp.common.dto.ApiResponse;
import ge.tastyerp.common.exception.*;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.stream.Collectors;

/**
 * Global exception handler for all services.
 * Converts exceptions to standardized API responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Check if the response has already been committed (body already written).
     * If committed, we should not attempt to write another response.
     */
    private boolean isResponseCommitted() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletResponse response = attrs.getResponse();
            if (response != null && response.isCommitted()) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write error for: {}", ex.getMessage());
            return null;
        }
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write error for: {}", ex.getMessage());
            return null;
        }
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write error for: {}", ex.getMessage());
            return null;
        }
        log.warn("Duplicate resource: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write validation errors");
            return null;
        }
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        log.warn("Validation errors: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(errors, "TASTY_ERR_400"));
    }

    /**
     * A missing or unparseable request parameter is the caller's mistake, not a
     * server fault. Without this it fell through to the catch-all below and
     * returned 500 "An unexpected error occurred", which tells the caller nothing
     * about what to fix.
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequestParameter(Exception ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write parameter error");
            return null;
        }
        String message = ex instanceof MissingServletRequestParameterException missing
                ? "Required parameter '" + missing.getParameterName() + "' is missing"
                : "Parameter '" + ((MethodArgumentTypeMismatchException) ex).getName()
                        + "' has an invalid value";
        log.warn("Bad request parameter: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, "TASTY_ERR_400"));
    }

    @ExceptionHandler(TastyErpException.class)
    public ResponseEntity<ApiResponse<Void>> handleTastyErpException(TastyErpException ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write error for: {}", ex.getMessage());
            return null;
        }
        log.error("Application error: {}", ex.getMessage());
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleExternalServiceException(ExternalServiceException ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write error for: {}", ex.getMessage());
            return null;
        }
        log.error("External service error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        if (isResponseCommitted()) {
            log.warn("Response already committed, cannot write error. Original exception: {}", ex.getMessage(), ex);
            return null;
        }
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", "TASTY_ERR_500"));
    }
}
