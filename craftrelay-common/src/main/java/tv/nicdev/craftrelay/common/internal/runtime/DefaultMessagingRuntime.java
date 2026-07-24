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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.common.internal.concurrent.ListenerDispatcher;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.protocol.MessageCodec;
import tv.nicdev.craftrelay.common.transport.NetworkTransport;

final class DefaultMessagingRuntime implements MessagingRuntime {

    private static final System.Logger LOGGER =
            System.getLogger(DefaultMessagingRuntime.class.getName());

    private final Object lifecycleLock = new Object();
    private final NetworkTransport transport;
    private final MessageCodec codec;
    private final LocalInstanceIdentity identity;
    private final MessagingRuntimeConfig config;
    private final DuplicateMessageCache duplicateCache;
    private final ListenerDispatcher listenerDispatcher =
            new ListenerDispatcher("craftrelay-runtime-listener-");
    private final Map<Class<? extends NetworkMessage>, List<RuntimeRegistration>>
            typedRegistrations = new HashMap<>();
    private final List<RuntimeRegistration> metadataRegistrations = new ArrayList<>();

    private volatile MessagingRuntimeState state = MessagingRuntimeState.NEW;
    private CompletableFuture<Void> startFuture;
    private CompletableFuture<Void> closeFuture;
    private Subscription transportSubscription;

    DefaultMessagingRuntime(
            NetworkTransport transport,
            MessageCodec codec,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig config) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.config = Objects.requireNonNull(config, "config");
        duplicateCache = new DuplicateMessageCache(config.duplicateCacheCapacity());
    }

    @Override
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> operation;
        synchronized (lifecycleLock) {
            if (state == MessagingRuntimeState.RUNNING) {
                return startFuture;
            }
            if (state == MessagingRuntimeState.STARTING) {
                return startFuture;
            }
            if (state == MessagingRuntimeState.STOPPING
                    || state == MessagingRuntimeState.STOPPED) {
                return failedFuture("messaging runtime is stopping or stopped");
            }

            state = MessagingRuntimeState.STARTING;
            operation = new CompletableFuture<>();
            startFuture = operation;
            try {
                transportSubscription =
                        transport.subscribe(config.messageChannel(), this::receive);
            } catch (RuntimeException failure) {
                state = MessagingRuntimeState.NEW;
                operation.completeExceptionally(failure);
                return operation;
            }
        }

        CompletableFuture<Void> connection;
        try {
            connection = Objects.requireNonNull(transport.connect(), "transport.connect()");
        } catch (RuntimeException failure) {
            completeStart(operation, failure);
            return operation;
        }
        connection.whenComplete((ignored, failure) -> completeStart(operation, failure));
        return operation;
    }

    @Override
    public CompletableFuture<Void> publish(NetworkTarget target, NetworkMessage message) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        if (state != MessagingRuntimeState.RUNNING) {
            return failedFuture("messaging runtime is not running");
        }

        byte[] encoded;
        try {
            encoded =
                    codec.encode(
                            identity.instanceId(), target, message, Optional.empty());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        try {
            return Objects.requireNonNull(
                    transport.publish(config.messageChannel(), encoded),
                    "transport.publish()");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public <M extends NetworkMessage> Subscription subscribe(
            Class<M> messageType, Consumer<? super M> listener) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(listener, "listener");

        RuntimeRegistration registration;
        synchronized (lifecycleLock) {
            ensureSubscriptionsAllowed();
            registration = createTypedRegistration(messageType, listener);
            typedRegistrations
                    .computeIfAbsent(messageType, ignored -> new ArrayList<>())
                    .add(registration);
        }
        RuntimeRegistration captured = registration;
        return Subscription.create(() -> removeTypedRegistration(messageType, captured));
    }

    Subscription subscribeDecoded(Consumer<? super DecodedMessage> listener) {
        Objects.requireNonNull(listener, "listener");
        RuntimeRegistration registration;
        synchronized (lifecycleLock) {
            ensureSubscriptionsAllowed();
            registration = createMetadataRegistration(listener);
            metadataRegistrations.add(registration);
        }
        RuntimeRegistration captured = registration;
        return Subscription.create(() -> removeMetadataRegistration(captured));
    }

    @Override
    public MessagingRuntimeState state() {
        return state;
    }

    @Override
    public CompletableFuture<Void> close() {
        CompletableFuture<Void> operation;
        CompletableFuture<Void> activeStart;
        Subscription receiveSubscription;
        List<RuntimeRegistration> registrations;
        synchronized (lifecycleLock) {
            if (state == MessagingRuntimeState.STOPPED) {
                return closeFuture;
            }
            if (state == MessagingRuntimeState.STOPPING) {
                return closeFuture;
            }

            state = MessagingRuntimeState.STOPPING;
            operation = new CompletableFuture<>();
            closeFuture = operation;
            activeStart = startFuture;
            receiveSubscription = transportSubscription;
            transportSubscription = null;
            registrations = allRegistrations();
            typedRegistrations.clear();
            metadataRegistrations.clear();
        }

        Throwable cleanupFailure = null;
        if (receiveSubscription != null) {
            try {
                receiveSubscription.close();
            } catch (Throwable failure) {
                cleanupFailure = mergeFailures(cleanupFailure, failure);
            }
        }
        if (activeStart != null && !activeStart.isDone()) {
            activeStart.completeExceptionally(
                    new IllegalStateException("messaging runtime stopped during start"));
        }
        for (RuntimeRegistration registration : registrations) {
            try {
                registration.close();
            } catch (Throwable failure) {
                cleanupFailure = mergeFailures(cleanupFailure, failure);
            }
        }

        CompletableFuture<Void> transportClose;
        try {
            transportClose = Objects.requireNonNull(transport.close(), "transport.close()");
        } catch (Throwable failure) {
            finishClose(operation, mergeFailures(cleanupFailure, failure));
            return operation;
        }
        Throwable priorFailure = cleanupFailure;
        transportClose.whenComplete(
                (ignored, failure) ->
                        finishClose(operation, mergeFailures(priorFailure, failure)));
        return operation;
    }

    private void completeStart(CompletableFuture<Void> operation, Throwable failure) {
        Subscription failedSubscription = null;
        Throwable completionFailure = failure;
        synchronized (lifecycleLock) {
            if (startFuture != operation) {
                return;
            }
            if (failure == null && state == MessagingRuntimeState.STARTING) {
                state = MessagingRuntimeState.RUNNING;
            } else {
                if (state == MessagingRuntimeState.STARTING) {
                    state = MessagingRuntimeState.NEW;
                    failedSubscription = transportSubscription;
                    transportSubscription = null;
                }
                if (completionFailure == null) {
                    completionFailure =
                            new IllegalStateException("messaging runtime stopped during start");
                }
            }
        }
        if (failedSubscription != null) {
            failedSubscription.close();
        }
        if (completionFailure == null) {
            operation.complete(null);
        } else {
            operation.completeExceptionally(completionFailure);
        }
    }

    private void finishClose(CompletableFuture<Void> operation, Throwable failure) {
        Throwable completionFailure = failure;
        try {
            listenerDispatcher.close();
        } catch (Throwable dispatcherFailure) {
            completionFailure = mergeFailures(completionFailure, dispatcherFailure);
        } finally {
            synchronized (lifecycleLock) {
                state = MessagingRuntimeState.STOPPED;
            }
        }
        if (completionFailure == null) {
            operation.complete(null);
        } else {
            operation.completeExceptionally(completionFailure);
        }
    }

    private void receive(String channel, byte[] payload) {
        if (!config.messageChannel().equals(channel)
                || state != MessagingRuntimeState.RUNNING) {
            return;
        }

        DecodedMessage decoded;
        try {
            decoded = codec.decode(payload);
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Discarding invalid CraftRelay envelope: {0}",
                    failure.getMessage());
            return;
        }
        if (!TargetMatcher.matches(decoded.target(), identity)
                || !duplicateCache.markIfNew(decoded.messageId())) {
            return;
        }

        List<RuntimeRegistration> metadata;
        List<RuntimeRegistration> typed;
        synchronized (lifecycleLock) {
            if (state != MessagingRuntimeState.RUNNING) {
                return;
            }
            metadata = List.copyOf(metadataRegistrations);
            typed = List.copyOf(
                    typedRegistrations.getOrDefault(decoded.message().getClass(), List.of()));
        }
        metadata.forEach(registration -> registration.dispatch(decoded));
        typed.forEach(registration -> registration.dispatch(decoded));
    }

    private <M extends NetworkMessage> RuntimeRegistration createTypedRegistration(
            Class<M> messageType, Consumer<? super M> listener) {
        return new RuntimeRegistration(listenerDispatcher.register(
                ListenerDispatcher.DEFAULT_QUEUE_CAPACITY,
                decoded -> listener.accept(messageType.cast(decoded.message())),
                failure -> logListenerFailure(messageType.getName(), failure),
                () -> logOverflow(messageType.getName())));
    }

    private RuntimeRegistration createMetadataRegistration(
            Consumer<? super DecodedMessage> listener) {
        return new RuntimeRegistration(listenerDispatcher.register(
                ListenerDispatcher.DEFAULT_QUEUE_CAPACITY,
                listener,
                failure -> logListenerFailure("metadata", failure),
                () -> logOverflow("metadata")));
    }

    private void removeTypedRegistration(
            Class<? extends NetworkMessage> messageType, RuntimeRegistration registration) {
        synchronized (lifecycleLock) {
            List<RuntimeRegistration> registrations = typedRegistrations.get(messageType);
            if (registrations != null && registrations.remove(registration)
                    && registrations.isEmpty()) {
                typedRegistrations.remove(messageType);
            }
        }
        registration.close();
    }

    private void removeMetadataRegistration(RuntimeRegistration registration) {
        synchronized (lifecycleLock) {
            metadataRegistrations.remove(registration);
        }
        registration.close();
    }

    private List<RuntimeRegistration> allRegistrations() {
        List<RuntimeRegistration> registrations = new ArrayList<>(metadataRegistrations);
        typedRegistrations.values().forEach(registrations::addAll);
        return registrations;
    }

    private void ensureSubscriptionsAllowed() {
        if (state == MessagingRuntimeState.STOPPING
                || state == MessagingRuntimeState.STOPPED) {
            throw new IllegalStateException("messaging runtime is stopping or stopped");
        }
    }

    private static void logListenerFailure(String listenerType, Throwable failure) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Messaging listener {0} failed: {1}",
                listenerType,
                failure.getMessage());
    }

    private static void logOverflow(String listenerType) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Dropping delivery for slow messaging listener {0}; queue limit is {1}",
                listenerType,
                ListenerDispatcher.DEFAULT_QUEUE_CAPACITY);
    }

    private static CompletableFuture<Void> failedFuture(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private static Throwable mergeFailures(Throwable first, Throwable second) {
        if (first == null) {
            return second;
        }
        if (second != null && second != first) {
            first.addSuppressed(second);
        }
        return first;
    }

    private record RuntimeRegistration(
            ListenerDispatcher.DispatchLane<DecodedMessage> dispatchLane) {

        private void dispatch(DecodedMessage decoded) {
            dispatchLane.dispatch(decoded);
        }

        private void close() {
            dispatchLane.close();
        }
    }
}
