package tv.nicdev.craftrelay.api.exception;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CraftRelayExceptionTest {

    @Test
    void preservesCause() {
        IllegalStateException cause = new IllegalStateException("transport failed");

        CraftRelayException exception = new CraftRelayException("Could not publish", cause);

        assertSame(cause, exception.getCause());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> new CraftRelayException(null));
        assertThrows(
                NullPointerException.class,
                () -> new CraftRelayException("Could not publish", null));
    }
}
