package ge.tastyerp.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A read or write against the data store (Firestore) failed, timed out or was
 * interrupted.
 *
 * <p>Maps to HTTP 503 so a caller can tell "the store is unavailable" apart from
 * "the request was wrong" (400) or "the code is broken" (500). Crucially it is
 * an exception, not an empty result: a financial system must never present an
 * outage as "no payments" or "no mappings" (BOR-81 finding B-3).</p>
 */
public class DataStoreException extends TastyErpException {

    public DataStoreException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "TASTY_ERR_503");
        if (cause != null) {
            initCause(cause);
        }
    }
}
