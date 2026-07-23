package tv.nicdev.craftrelay.common.internal.protocol;

import java.util.Optional;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

interface MessageCodec {

    byte[] encode(
            String sourceInstance,
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId);

    DecodedMessage decode(byte[] encoded);
}
