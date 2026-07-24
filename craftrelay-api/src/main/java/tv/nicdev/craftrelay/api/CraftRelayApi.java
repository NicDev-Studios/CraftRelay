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
package tv.nicdev.craftrelay.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

/**
 * Thread-safe, non-blocking access to a CraftRelay network.
 *
 * <p>Implementations must validate all arguments before scheduling work. Futures complete
 * exceptionally when an operation cannot be completed. Message listeners may run on any thread
 * and must not perform blocking work.
 */
public interface CraftRelayApi {

    /**
     * Publishes a message to the selected network target.
     *
     * @param target destination of the message
     * @param message message to publish
     * @return a future completed once the message has been accepted for delivery
     */
    CompletableFuture<Void> publish(NetworkTarget target, NetworkMessage message);

    /**
     * Subscribes to messages of exactly the requested public message type.
     *
     * @param messageType type to subscribe to
     * @param listener non-blocking message listener
     * @param <M> message type
     * @return an idempotently closeable subscription
     */
    <M extends NetworkMessage> Subscription subscribe(
            Class<M> messageType, Consumer<? super M> listener);

    /**
     * Sends a request and awaits its correlated response.
     *
     * @param target destination of the request
     * @param request request message
     * @param responseType expected response type
     * @param timeout positive maximum response duration
     * @param <R> response type
     * @return a future containing the response, or completed exceptionally on failure
     */
    <R extends NetworkMessage> CompletableFuture<R> request(
            NetworkTarget target,
            NetworkMessage request,
            Class<R> responseType,
            Duration timeout);

    /**
     * Returns the currently known network instances.
     *
     * @return a future containing an immutable point-in-time snapshot
     */
    CompletableFuture<Collection<NetworkInstance>> instances();

    /**
     * Looks up a player by unique ID.
     *
     * @param playerId player unique ID
     * @return a future containing the player when currently known
     */
    CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId);

    /**
     * Returns the current local API lifecycle state without blocking.
     *
     * @return current lifecycle state
     */
    CraftRelayState state();
}
