package tv.nicdev.craftrelay.api.message;

import java.time.Instant;
import java.util.Objects;

final class MessageValidation {

    private MessageValidation() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Instant requireInstant(Instant value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
