package tv.nicdev.craftrelay.api.message;

import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstance;

/**
 * Announces that an instance has joined the network.
 *
 * @param instance immutable initial instance snapshot
 */
public record InstanceStartedMessage(NetworkInstance instance) implements NetworkMessage {

    /** Creates a validated instance-started message. */
    public InstanceStartedMessage {
        instance = Objects.requireNonNull(instance, "instance");
    }
}
