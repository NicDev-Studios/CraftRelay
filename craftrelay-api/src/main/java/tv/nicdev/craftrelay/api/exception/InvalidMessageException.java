package tv.nicdev.craftrelay.api.exception;

/**
 * Indicates that a message does not satisfy the public messaging contract.
 */
public final class InvalidMessageException extends CraftRelayException {

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
