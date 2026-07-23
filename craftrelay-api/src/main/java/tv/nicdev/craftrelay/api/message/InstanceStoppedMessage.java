package tv.nicdev.craftrelay.api.message;

import java.time.Instant;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Announces a graceful instance shutdown.
 *
 * @param instanceId network-unique instance ID
 * @param stoppedAt shutdown time
 */
public record InstanceStoppedMessage(String instanceId, Instant stoppedAt)
        implements NetworkMessage {

    /** Creates a validated instance-stopped message. */
    public InstanceStoppedMessage {
        instanceId = MessageValidation.requireText(instanceId, "instanceId");
        stoppedAt = MessageValidation.requireInstant(stoppedAt, "stoppedAt");
    }
}
