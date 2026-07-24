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
package tv.nicdev.craftrelay.common.internal.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

/**
 * Internal asynchronous messaging runtime used by CraftRelay implementation modules.
 *
 * <p>Implementations are thread-safe. Listener callbacks are isolated from transport I/O and from
 * other listeners.
 */
public interface MessagingRuntime {

    /**
     * Starts the receive path and underlying transport.
     *
     * @return shared future for the active start operation
     */
    CompletableFuture<Void> start();

    /**
     * Publishes one message from the local instance.
     *
     * @param target routing target
     * @param message message payload
     * @return future completed after the transport accepts the message
     */
    CompletableFuture<Void> publish(NetworkTarget target, NetworkMessage message);

    /**
     * Registers a typed listener.
     *
     * @param messageType exact message class
     * @param listener listener callback
     * @param <M> message type
     * @return idempotent registration
     */
    <M extends NetworkMessage> Subscription subscribe(
            Class<M> messageType, Consumer<? super M> listener);

    /**
     * Returns the current runtime state.
     *
     * @return current state
     */
    MessagingRuntimeState state();

    /**
     * Stops delivery and closes owned runtime resources and the transport.
     *
     * @return shared future for the active close operation
     */
    CompletableFuture<Void> close();
}
