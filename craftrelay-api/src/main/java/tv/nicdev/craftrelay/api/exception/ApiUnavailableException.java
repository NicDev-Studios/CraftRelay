package tv.nicdev.craftrelay.api.exception;

/**
 * Indicates that the local CraftRelay API is not available for an operation.
 */
public final class ApiUnavailableException extends CraftRelayException {

    /**
     * Creates an API-unavailable failure.
     *
     * @param message failure description
     */
    public ApiUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates an API-unavailable failure with its cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public ApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
