package tv.nicdev.craftrelay.api.exception;

import java.io.Serial;

/**
 * Indicates that the local CraftRelay API is not available for an operation.
 */
public final class ApiUnavailableException extends CraftRelayException {

    @Serial
    private static final long serialVersionUID = 1L;

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
