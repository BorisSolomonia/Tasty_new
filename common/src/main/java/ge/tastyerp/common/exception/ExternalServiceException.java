package ge.tastyerp.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when an external service (RS.ge, Banking API) fails.
 */
public class ExternalServiceException extends TastyErpException {

    private final String serviceName;

    public ExternalServiceException(String serviceName, String message) {
        super(
            String.format("External service '%s' error: %s", serviceName, message),
            HttpStatus.BAD_GATEWAY,
            "TASTY_ERR_502"
        );
        this.serviceName = serviceName;
    }

    public ExternalServiceException(String serviceName, String message, Throwable cause) {
        super(
            String.format("External service '%s' error: %s", serviceName, message),
            HttpStatus.BAD_GATEWAY,
            "TASTY_ERR_502"
        );
        this.serviceName = serviceName;
        if (cause != null) {
            initCause(cause);
        }
    }

    public String getServiceName() {
        return serviceName;
    }
}
