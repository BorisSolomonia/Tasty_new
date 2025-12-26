package ge.tastyerp.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all Tasty ERP business exceptions.
 */
@Getter
public class TastyErpException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public TastyErpException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.errorCode = "TASTY_ERR_001";
    }

    public TastyErpException(String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.errorCode = "TASTY_ERR_001";
    }

    public TastyErpException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public TastyErpException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.errorCode = "TASTY_ERR_001";
    }
}
