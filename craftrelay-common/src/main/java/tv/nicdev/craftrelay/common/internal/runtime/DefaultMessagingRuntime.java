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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.concurrent.ListenerDispatcher;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.protocol.CodecRegistration;
import tv.nicdev.craftrelay.common.internal.protocol.MessageBindingKey;
import tv.nicdev.craftrelay.common.internal.protocol.MessageCodec;
import tv.nicdev.craftrelay.common.internal.protocol.PreparedMessage;
import tv.nicdev.craftrelay.common.internal.protocol.PreparedOutboundMessage;
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
    private final Map<MessageBindingKey, ListenerDispatcher.DispatchLane<PublishOperation>>
            publishLanes = new HashMap<>();
    private final Map<MessageBindingKey, ListenerDispatcher.DispatchLane<PreparedMessage>>
            decodeLanes =
            new HashMap<>();
    private final Map<MessageBindingKey, DefaultRuntimeMessageRegistration<?>>
            customRegistrations = new HashMap<>();
    private final Set<CompletableFuture<Void>> activePublishes =
            ConcurrentHashMap.newKeySet();

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
    public CompletableFuture<Void> publish(
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");
        return prepareAndPublish(
                () -> codec.prepare(
                        identity.instanceId(), target, message, correlationId));
    }

    @Override
    public <M extends NetworkMessage> CompletableFuture<Void> publish(
            RuntimeMessageRegistration<M> registration,
            NetworkTarget target,
            M message,
            Optional<UUID> correlationId) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(correlationId, "correlationId");
        if (!(registration instanceof DefaultRuntimeMessageRegistration<?> candidate)
                || candidate.owner() != this) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "message registration belongs to another runtime"));
        }
        @SuppressWarnings("unchecked")
        DefaultRuntimeMessageRegistration<M> owned =
                (DefaultRuntimeMessageRegistration<M>) candidate;
        return prepareAndPublish(
                () -> codec.prepare(
                        owned.codecRegistration(),
                        identity.instanceId(),
                        target,
                        message,
                        correlationId));
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

    @Override
    public Subscription subscribeDecoded(Consumer<? super DecodedMessage> listener) {
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
    public <M extends NetworkMessage> RuntimeMessageRegistration<M> registerMessageType(
            MessageType<M> type, MessagePayloadCodec<M> payloadCodec) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadCodec, "payloadCodec");
        synchronized (lifecycleLock) {
            if (state != MessagingRuntimeState.RUNNING) {
                throw new IllegalStateException("messaging runtime is not running");
            }
            CodecRegistration<M> codecRegistration = codec.register(type, payloadCodec);
            DefaultRuntimeMessageRegistration<M> registration =
                    new DefaultRuntimeMessageRegistration<>(this, codecRegistration);
            customRegistrations.put(registration.bindingKey(), registration);
            return registration;
        }
    }

    @Override
    public boolean isMessageTypeRegistered(MessageType<?> type) {
        Objects.requireNonNull(type, "type");
        synchronized (lifecycleLock) {
            return state == MessagingRuntimeState.RUNNING && codec.isRegistered(type);
        }
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
        List<CompletableFuture<Void>> publications;
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
            publishLanes.clear();
            decodeLanes.clear();
            customRegistrations.values()
                    .forEach(registration -> registration.codecRegistration().close());
            customRegistrations.clear();
            publications = List.copyOf(activePublishes);
            activePublishes.clear();
        }

        Throwable cleanupFailure = null;
        if (receiveSubscription != null) {
            try {
                receiveSubscription.close();
            } catch (Throwable failure) {
                cleanupFailure = AsyncFailures.merge(cleanupFailure, failure);
            }
        }
        if (activeStart != null && !activeStart.isDone()) {
            activeStart.completeExceptionally(
                    new IllegalStateException("messaging runtime stopped during start"));
        }
        publications.forEach(
                publication ->
                        publication.completeExceptionally(
                                new IllegalStateException(
                                        "messaging runtime stopped during publish")));
        for (RuntimeRegistration registration : registrations) {
            try {
                registration.close();
            } catch (Throwable failure) {
                cleanupFailure = AsyncFailures.merge(cleanupFailure, failure);
            }
        }

        CompletableFuture<Void> transportClose;
        try {
            transportClose = Objects.requireNonNull(transport.close(), "transport.close()");
        } catch (Throwable failure) {
            finishClose(operation, AsyncFailures.merge(cleanupFailure, failure));
            return operation;
        }
        Throwable priorFailure = cleanupFailure;
        transportClose.whenComplete(
                (ignored, failure) ->
                        finishClose(
                                operation,
                                AsyncFailures.merge(priorFailure, failure)));
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
            completionFailure =
                    AsyncFailures.merge(completionFailure, dispatcherFailure);
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

        PreparedMessage prepared;
        try {
            prepared = codec.prepare(payload);
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Discarding invalid CraftRelay envelope: {0}",
                    failure.getMessage());
            return;
        }
        ListenerDispatcher.DispatchLane<PreparedMessage> lane;
        synchronized (lifecycleLock) {
            if (state != MessagingRuntimeState.RUNNING
                    || !codec.isActive(prepared.bindingKey())) {
                return;
            }
            lane = decodeLanes.computeIfAbsent(
                    prepared.bindingKey(), this::createDecodeLane);
            if (!lane.dispatch(prepared)) {
                logOverflow("codec " + prepared.bindingKey().diagnosticName());
            }
        }
    }

    private void decodeAndDeliver(PreparedMessage prepared) {
        DecodedMessage decoded;
        try {
            decoded = codec.decode(prepared);
        } catch (RuntimeException failure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Discarding invalid CraftRelay payload: {0}",
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

    private ListenerDispatcher.DispatchLane<PublishOperation> createPublishLane(
            MessageBindingKey bindingKey) {
        return listenerDispatcher.register(
                ListenerDispatcher.DEFAULT_QUEUE_CAPACITY,
                this::encodeAndPublish,
                failure ->
                        logListenerFailure(
                                "codec " + bindingKey.diagnosticName(), failure),
                () -> logOverflow("codec " + bindingKey.diagnosticName()));
    }

    private ListenerDispatcher.DispatchLane<PreparedMessage> createDecodeLane(
            MessageBindingKey bindingKey) {
        return listenerDispatcher.register(
                ListenerDispatcher.DEFAULT_QUEUE_CAPACITY,
                this::decodeAndDeliver,
                failure ->
                        logListenerFailure(
                                "codec " + bindingKey.diagnosticName(), failure),
                () -> logOverflow("codec " + bindingKey.diagnosticName()));
    }

    private void encodeAndPublish(PublishOperation publication) {
        if (state != MessagingRuntimeState.RUNNING) {
            publication.result().completeExceptionally(
                    new IllegalStateException("messaging runtime is not running"));
            return;
        }
        byte[] encoded;
        try {
            encoded = codec.encode(publication.prepared());
        } catch (RuntimeException failure) {
            publication.result().completeExceptionally(failure);
            return;
        }
        if (state != MessagingRuntimeState.RUNNING) {
            publication.result().completeExceptionally(
                    new IllegalStateException("messaging runtime stopped during encoding"));
            return;
        }
        CompletableFuture<Void> publish;
        try {
            publish =
                    Objects.requireNonNull(
                            transport.publish(config.messageChannel(), encoded),
                            "transport.publish()");
        } catch (RuntimeException failure) {
            publication.result().completeExceptionally(failure);
            return;
        }
        publish.whenComplete(
                (ignored, failure) -> {
                    if (failure == null) {
                        publication.result().complete(null);
                    } else {
                        publication.result().completeExceptionally(
                                AsyncFailures.unwrap(failure));
                    }
                });
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

    private CompletableFuture<Void> prepareAndPublish(
            Supplier<PreparedOutboundMessage> preparation) {
        CompletableFuture<Void> operation = new CompletableFuture<>();
        PublishOperation publication;
        synchronized (lifecycleLock) {
            if (state != MessagingRuntimeState.RUNNING) {
                return failedFuture("messaging runtime is not running");
            }
            PreparedOutboundMessage prepared;
            ListenerDispatcher.DispatchLane<PublishOperation> lane;
            try {
                prepared = Objects.requireNonNull(
                        preparation.get(), "prepared outbound message");
                lane = publishLanes.computeIfAbsent(
                        prepared.bindingKey(), this::createPublishLane);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            activePublishes.add(operation);
            publication = new PublishOperation(prepared, operation);
            if (!lane.dispatch(publication)) {
                operation.completeExceptionally(
                        new IllegalStateException(
                                "message codec queue is full or closed for "
                                        + prepared.bindingKey().diagnosticName()));
            }
        }
        operation.whenComplete(
                (ignored, failure) ->
                        releasePublication(publication));
        return operation;
    }

    private void releasePublication(PublishOperation publication) {
        activePublishes.remove(publication.result());
        ListenerDispatcher.DispatchLane<PublishOperation> retiredLane = null;
        synchronized (lifecycleLock) {
            if (!codec.isActive(publication.prepared().bindingKey())) {
                retiredLane = publishLanes.remove(
                        publication.prepared().bindingKey());
            }
        }
        if (retiredLane != null) {
            retiredLane.closeAfterDrain();
        }
    }

    private void unregister(DefaultRuntimeMessageRegistration<?> registration) {
        ListenerDispatcher.DispatchLane<PublishOperation> publishLane;
        ListenerDispatcher.DispatchLane<PreparedMessage> decodeLane;
        synchronized (lifecycleLock) {
            if (!customRegistrations.remove(
                    registration.bindingKey(), registration)) {
                return;
            }
            registration.codecRegistration().close();
            publishLane = publishLanes.remove(registration.bindingKey());
            decodeLane = decodeLanes.remove(registration.bindingKey());
        }
        if (publishLane != null) {
            publishLane.closeAfterDrain();
        }
        if (decodeLane != null) {
            decodeLane.closeAfterDrain();
        }
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

    private record PublishOperation(
            PreparedOutboundMessage prepared, CompletableFuture<Void> result) {

        private PublishOperation {
            Objects.requireNonNull(prepared, "prepared");
            Objects.requireNonNull(result, "result");
        }
    }

    private record DefaultRuntimeMessageRegistration<M extends NetworkMessage>(
            DefaultMessagingRuntime owner,
            CodecRegistration<M> codecRegistration)
            implements RuntimeMessageRegistration<M> {

        private DefaultRuntimeMessageRegistration {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(codecRegistration, "codecRegistration");
        }

        @Override
        public MessageType<M> type() {
            return codecRegistration.type();
        }

        @Override
        public MessageBindingKey bindingKey() {
            return codecRegistration.bindingKey();
        }

        @Override
        public boolean isClosed() {
            return codecRegistration.isClosed();
        }

        @Override
        public void close() {
            owner.unregister(this);
        }
    }
}
