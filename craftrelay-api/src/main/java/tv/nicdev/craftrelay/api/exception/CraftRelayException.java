package tv.nicdev.craftrelay.api.exception;

import java.util.Objects;

/**
 * Base class for failures reported by the public CraftRelay API.
 */
public class CraftRelayException extends RuntimeException {

    /**
     * Creates an exception with a descriptive message.
     *
     * @param message failure description
     */
    public CraftRelayException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    /**
     * Creates an exception with a description and underlying cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public CraftRelayException(String message, Throwable cause) {
        super(
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(cause, "cause"));
    }
}
