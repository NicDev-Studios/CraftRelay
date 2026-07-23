package tv.nicdev.craftrelay.api.exception;

import java.io.Serial;

/**
 * Indicates that a message does not satisfy the public messaging contract.
 */
public final class InvalidMessageException extends CraftRelayException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an invalid-message failure.
     *
     * @param message failure description
     */
    public InvalidMessageException(String message) {
        super(message);
    }

    /**
     * Creates an invalid-message failure with its cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
