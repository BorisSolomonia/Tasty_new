package ge.tastyerp.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when validation fails.
 */
public class ValidationException extends TastyErpException {

    public ValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "TASTY_ERR_400");
    }

    public ValidationException(String field, String message) {
        super(
            String.format("Validation failed for '%s': %s", field, message),
            HttpStatus.BAD_REQUEST,
            "TASTY_ERR_400"
        );
    }
}
