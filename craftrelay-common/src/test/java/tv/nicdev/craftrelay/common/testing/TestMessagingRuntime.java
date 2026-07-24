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
package tv.nicdev.craftrelay.common.testing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeState;

/**
 * Controllable metadata-aware messaging runtime for request-layer unit tests.
 */
public final class TestMessagingRuntime implements MessagingRuntime {

    private final List<Consumer<? super DecodedMessage>> metadataListeners =
            new CopyOnWriteArrayList<>();

    private volatile MessagingRuntimeState state = MessagingRuntimeState.RUNNING;
    private volatile PublishHook publishHook = published -> {};
    private volatile PublishedMessage lastPublished;

    /**
     * Installs a hook invoked synchronously for each publish.
     *
     * @param hook publish hook
     */
    public void onPublish(PublishHook hook) {
        publishHook = Objects.requireNonNull(hook, "hook");
    }

    /**
     * Returns the most recently published message.
     *
     * @return last publish, or {@code null} when none occurred
     */
    public PublishedMessage lastPublished() {
        return lastPublished;
    }

    /**
     * Emits a decoded message to every metadata listener.
     *
     * @param sourceInstance source instance ID
     * @param target target
     * @param correlationId optional correlation ID
     * @param message message payload
     */
    public void emit(
            String sourceInstance,
            NetworkTarget target,
            Optional<UUID> correlationId,
            NetworkMessage message) {
        DecodedMessage decoded =
                new DecodedMessage(
                        UUID.randomUUID(),
                        sourceInstance,
                        target,
                        Instant.now(),
                        correlationId,
                        message);
        metadataListeners.forEach(listener -> listener.accept(decoded));
    }

    @Override
    public CompletableFuture<Void> start() {
        state = MessagingRuntimeState.RUNNING;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> publish(
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId) {
        PublishedMessage published =
                new PublishedMessage(target, message, correlationId);
        lastPublished = published;
        publishHook.onPublish(published);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <M extends NetworkMessage> Subscription subscribe(
            Class<M> messageType, Consumer<? super M> listener) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(listener, "listener");
        Consumer<DecodedMessage> adapter =
                decoded -> {
                    if (decoded.message().getClass() == messageType) {
                        listener.accept(messageType.cast(decoded.message()));
                    }
                };
        metadataListeners.add(adapter);
        return Subscription.create(() -> metadataListeners.remove(adapter));
    }

    @Override
    public Subscription subscribeDecoded(
            Consumer<? super DecodedMessage> listener) {
        metadataListeners.add(Objects.requireNonNull(listener, "listener"));
        return Subscription.create(() -> metadataListeners.remove(listener));
    }

    @Override
    public MessagingRuntimeState state() {
        return state;
    }

    @Override
    public CompletableFuture<Void> close() {
        state = MessagingRuntimeState.STOPPED;
        metadataListeners.clear();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Captured publish call.
     *
     * @param target target
     * @param message message
     * @param correlationId optional correlation ID
     */
    public record PublishedMessage(
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId) {

        /** Creates validated captured data. */
        public PublishedMessage {
            target = Objects.requireNonNull(target, "target");
            message = Objects.requireNonNull(message, "message");
            correlationId = Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    /**
     * Synchronous test hook for a publish call.
     */
    @FunctionalInterface
    public interface PublishHook {

        /**
         * Observes a publish.
         *
         * @param published captured publish data
         */
        void onPublish(PublishedMessage published);
    }
}
