package tv.nicdev.craftrelay.api.exception;

import java.io.Serial;

/**
 * Indicates that no correlated response arrived within the configured timeout.
 */
public final class RequestTimeoutException extends CraftRelayException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a request-timeout failure.
     *
     * @param message failure description
     */
    public RequestTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates a request-timeout failure with its cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public RequestTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
