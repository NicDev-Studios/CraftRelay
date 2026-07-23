package tv.nicdev.craftrelay.api.exception;

import java.io.Serial;

/**
 * Indicates an unsupported or malformed CraftRelay protocol interaction.
 */
public final class ProtocolException extends CraftRelayException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a protocol failure.
     *
     * @param message failure description
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Creates a protocol failure with its cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
