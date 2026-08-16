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
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.messaging.CustomMessaging;
import tv.nicdev.craftrelay.api.exception.RequestTimeoutException;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

/**
 * Thread-safe, non-blocking access to a CraftRelay network.
 *
 * <p>Implementations must validate all arguments before scheduling work. Futures complete
 * exceptionally when an operation cannot be completed. Message listeners may run on any thread
 * and must not perform blocking work.
 *
 * @since 0.1.0
 */
public interface CraftRelayApi {

    /**
     * Returns the local registry for explicitly allowed custom message types and request handlers.
     *
     * <p>Registrations are local to this node. Every node that sends or receives a custom message
     * must register the same identifier, payload version, and JSON representation.
     *
     * @return stable custom-messaging facade
     */
    CustomMessaging customMessaging();

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
     * <p>Requests to groups or broadcast targets use first-response-wins semantics. Delivery is
     * best-effort because the current transport uses Redis Pub/Sub.
     *
     * @param target destination of the request
     * @param request request message
     * @param responseType expected response type
     * @param timeout positive, finite maximum response duration
     * @param <R> response type
     * @return a future containing the first valid correlated response
     * @throws IllegalArgumentException if the timeout is not positive or the request and response
     *     use the same concrete Java type
     * @throws RequestTimeoutException through the returned future if no response arrives in time
     * @throws ApiUnavailableException through the returned future if the API cannot accept the
     *     request
     */
    <R extends NetworkMessage> CompletableFuture<R> request(
            NetworkTarget target,
            NetworkMessage request,
            Class<R> responseType,
            Duration timeout);

    /**
     * Returns the currently known network instances.
     *
     * <p>The snapshot is read from CraftRelay's authoritative Redis presence registry. A crashed
     * instance remains visible only until its configured lease TTL expires. The returned
     * collection is immutable. If the state store is unavailable, the future fails with
     * {@link ApiUnavailableException}.
     *
     * @return a future containing an immutable point-in-time snapshot
     * @throws ApiUnavailableException asynchronously when the presence store is unavailable
     */
    CompletableFuture<Collection<NetworkInstance>> instances();

    /**
     * Looks up a player by unique ID.
     *
     * <p>The immutable point-in-time snapshot is read from CraftRelay's authoritative Redis
     * presence registry. A player from a crashed proxy remains visible only until the configured
     * player TTL expires. If the state store is unavailable, the future fails with
     * {@link ApiUnavailableException}.
     *
     * @param playerId player unique ID
     * @return a future containing the player when currently known
     * @throws ApiUnavailableException asynchronously when the presence store is unavailable
     */
    CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId);

    /**
     * Returns the current local API lifecycle state without blocking.
     *
     * @return current lifecycle state
     */
    CraftRelayState state();
}
