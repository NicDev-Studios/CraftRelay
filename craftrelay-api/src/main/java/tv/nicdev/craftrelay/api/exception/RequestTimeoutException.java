package tv.nicdev.craftrelay.api.exception;

/**
 * Indicates that no correlated response arrived within the configured timeout.
 */
public final class RequestTimeoutException extends CraftRelayException {

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
