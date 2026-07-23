package tv.nicdev.craftrelay.api.message;

import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;

/**
 * Reports the latest state of a live network instance.
 *
 * @param instance immutable instance snapshot
 */
public record InstanceHeartbeatMessage(NetworkInstance instance) implements NetworkMessage {

    /** Creates a validated heartbeat message. */
    public InstanceHeartbeatMessage {
        instance = Objects.requireNonNull(instance, "instance");
    }
}
