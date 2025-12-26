package ge.tastyerp.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when attempting to create a duplicate resource.
 */
public class DuplicateResourceException extends TastyErpException {

    public DuplicateResourceException(String resourceType, String identifier) {
        super(
            String.format("%s already exists with identifier: %s", resourceType, identifier),
            HttpStatus.CONFLICT,
            "TASTY_ERR_409"
        );
    }

    public DuplicateResourceException(String message) {
        super(message, HttpStatus.CONFLICT, "TASTY_ERR_409");
    }
}
