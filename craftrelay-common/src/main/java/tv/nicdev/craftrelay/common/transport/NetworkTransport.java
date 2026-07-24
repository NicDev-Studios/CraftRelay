/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
