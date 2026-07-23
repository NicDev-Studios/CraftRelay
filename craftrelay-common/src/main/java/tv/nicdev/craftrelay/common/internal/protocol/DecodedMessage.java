package tv.nicdev.craftrelay.common.internal.protocol;

import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;

record DecodedMessage(MessageEnvelope envelope, NetworkMessage message) {

    DecodedMessage {
        envelope = Objects.requireNonNull(envelope, "envelope");
        message = Objects.requireNonNull(message, "message");
    }
}
