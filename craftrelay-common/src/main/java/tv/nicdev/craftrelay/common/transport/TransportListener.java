package tv.nicdev.craftrelay.common.transport;

/**
 * Receives opaque transport payloads.
 *
 * <p>The supplied payload is isolated from other listeners. Implementations catch listener
 * failures so one consumer cannot interrupt delivery to another consumer. Implementations must
 * dispatch callbacks away from transport I/O threads.
 */
@FunctionalInterface
public interface TransportListener {

    /**
     * Handles a transport message.
     *
     * @param channel source channel
     * @param payload independently owned payload bytes
     */
    void onMessage(String channel, byte[] payload);
}
