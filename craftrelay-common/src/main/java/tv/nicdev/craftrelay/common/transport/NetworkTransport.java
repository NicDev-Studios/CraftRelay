package tv.nicdev.craftrelay.common.transport;

import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.api.Subscription;

/**
 * Internal, transport-neutral byte messaging contract used by CraftRelay implementation modules.
 *
 * <p>All potentially blocking operations are asynchronous. Implementations must be thread-safe.
 * Listener callbacks run away from transport I/O threads and may use an implementation-managed
 * executor. Consumers should still avoid unnecessary blocking work.
 */
public interface NetworkTransport {

    /**
     * Establishes the transport connection.
     *
     * @return a future completed when the transport is ready
     */
    CompletableFuture<Void> connect();

    /**
     * Publishes an opaque payload.
     *
     * @param channel non-blank transport channel
     * @param payload payload bytes, defensively copied by the implementation
     * @return a future completed after the transport accepts the publish operation
     */
    CompletableFuture<Void> publish(String channel, byte[] payload);

    /**
     * Registers a listener immediately. Activation at the remote broker may happen asynchronously.
     *
     * @param channel non-blank transport channel
     * @param listener message listener
     * @return an idempotently closeable local registration
     */
    Subscription subscribe(String channel, TransportListener listener);

    /**
     * Returns the current lifecycle state.
     *
     * @return current state
     */
    TransportState state();

    /**
     * Closes connections and owned resources.
     *
     * @return a future completed after shutdown
     */
    CompletableFuture<Void> close();
}
