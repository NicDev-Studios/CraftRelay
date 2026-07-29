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
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticCode;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticComponent;
import tv.nicdev.craftrelay.common.internal.observability.NodeDiagnostics;
import tv.nicdev.craftrelay.common.internal.observability.TelemetryCounter;
import tv.nicdev.craftrelay.common.internal.observability.TelemetryGauge;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.protocol.CodecRegistration;
import tv.nicdev.craftrelay.common.internal.protocol.MessageBindingKey;
import tv.nicdev.craftrelay.common.internal.protocol.MessageCodec;
import tv.nicdev.craftrelay.common.internal.protocol.PreparedMessage;
import tv.nicdev.craftrelay.common.internal.protocol.PreparedOutboundMessage;
import tv.nicdev.craftrelay.common.transport.NetworkTransport;

final class DefaultMessagingRuntime implements MessagingRuntime {

    private final Object lifecycleLock = new Object();
    private final NetworkTransport transport;
    private final MessageCodec codec;
    private final LocalInstanceIdentity identity;
    private final MessagingRuntimeConfig config;
    private final NodeDiagnostics diagnostics;
    private final DuplicateMessageCache duplicateCache;
    private final ListenerDispatcher listenerDispatcher;
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
        this(transport, codec, identity, config, new NodeDiagnostics());
    }

    DefaultMessagingRuntime(
            NetworkTransport transport,
            MessageCodec codec,
            LocalInstanceIdentity identity,
            MessagingRuntimeConfig config,
            NodeDiagnostics diagnostics) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.config = Objects.requireNonNull(config, "config");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        listenerDispatcher =
                new ListenerDispatcher("craftrelay-runtime-listener-", diagnostics);
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
            if (typedListenerCount() >= config.capacities().maximumListeners()) {
                throw new IllegalStateException("maximum number of listeners reached");
            }
            registration = createTypedRegistration(messageType, listener);
            typedRegistrations
                    .computeIfAbsent(messageType, ignored -> new ArrayList<>())
                    .add(registration);
            diagnostics.setGauge(TelemetryGauge.LISTENERS, listenerCount());
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
            diagnostics.setGauge(TelemetryGauge.LISTENERS, listenerCount());
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
                diagnostics.healthy(DiagnosticComponent.MESSAGING);
                diagnostics.healthy(DiagnosticComponent.DISPATCHER);
                diagnostics.healthy(DiagnosticComponent.TRANSPORT);
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
            diagnostics.unavailable(DiagnosticComponent.MESSAGING);
            diagnostics.unavailable(DiagnosticComponent.DISPATCHER);
            diagnostics.unavailable(DiagnosticComponent.TRANSPORT);
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
        diagnostics.increment(TelemetryCounter.MESSAGES_RECEIVED);

        PreparedMessage prepared;
        try {
            prepared = codec.prepare(payload);
        } catch (RuntimeException failure) {
            diagnostics.increment(TelemetryCounter.MESSAGE_INVALID_DROPPED);
            diagnostics.report(DiagnosticCode.MESSAGE_DECODE_FAILED, failure);
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
            lane.dispatch(prepared);
        }
    }

    private void decodeAndDeliver(PreparedMessage prepared) {
        DecodedMessage decoded;
        try {
            decoded = codec.decode(prepared);
        } catch (RuntimeException failure) {
            diagnostics.increment(TelemetryCounter.MESSAGE_INVALID_DROPPED);
            diagnostics.report(DiagnosticCode.MESSAGE_DECODE_FAILED, failure);
            return;
        }
        diagnostics.increment(TelemetryCounter.MESSAGES_DECODED);
        if (!TargetMatcher.matches(decoded.target(), identity)) {
            return;
        }
        if (!duplicateCache.markIfNew(decoded.messageId())) {
            diagnostics.increment(TelemetryCounter.MESSAGE_DUPLICATES_DROPPED);
            return;
        }
        diagnostics.setGauge(TelemetryGauge.DUPLICATE_CACHE_SIZE, duplicateCache.size());

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
        diagnostics.increment(TelemetryCounter.MESSAGES_DELIVERED);
    }

    private ListenerDispatcher.DispatchLane<PublishOperation> createPublishLane(
            MessageBindingKey bindingKey) {
        return listenerDispatcher.register(
                config.capacities().dispatchQueueCapacity(),
                this::encodeAndPublish,
                failure -> diagnostics.report(DiagnosticCode.MESSAGE_CODEC_FAILED, failure),
                () -> {});
    }

    private ListenerDispatcher.DispatchLane<PreparedMessage> createDecodeLane(
            MessageBindingKey bindingKey) {
        return listenerDispatcher.register(
                config.capacities().dispatchQueueCapacity(),
                this::decodeAndDeliver,
                failure -> diagnostics.report(DiagnosticCode.MESSAGE_CODEC_FAILED, failure),
                () -> {});
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
            diagnostics.increment(TelemetryCounter.MESSAGE_CODEC_FAILURES);
            diagnostics.report(DiagnosticCode.MESSAGE_CODEC_FAILED, failure);
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
            recordPublishFailure(failure);
            publication.result().completeExceptionally(failure);
            return;
        }
        publish.whenComplete(
                (ignored, failure) -> {
                    if (failure == null) {
                        diagnostics.increment(TelemetryCounter.MESSAGES_SENT);
                        diagnostics.healthy(DiagnosticComponent.TRANSPORT);
                        publication.result().complete(null);
                    } else {
                        recordPublishFailure(AsyncFailures.unwrap(failure));
                        publication.result().completeExceptionally(
                                AsyncFailures.unwrap(failure));
                    }
                });
    }

    private <M extends NetworkMessage> RuntimeRegistration createTypedRegistration(
            Class<M> messageType, Consumer<? super M> listener) {
        return new RuntimeRegistration(listenerDispatcher.register(
                config.capacities().dispatchQueueCapacity(),
                decoded -> listener.accept(messageType.cast(decoded.message())),
                failure -> diagnostics.report(DiagnosticCode.LISTENER_FAILED, failure),
                () -> {}));
    }

    private RuntimeRegistration createMetadataRegistration(
            Consumer<? super DecodedMessage> listener) {
        return new RuntimeRegistration(listenerDispatcher.register(
                config.capacities().dispatchQueueCapacity(),
                listener,
                failure -> diagnostics.report(DiagnosticCode.LISTENER_FAILED, failure),
                () -> {}));
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
        diagnostics.setGauge(TelemetryGauge.LISTENERS, listenerCount());
        registration.close();
    }

    private void removeMetadataRegistration(RuntimeRegistration registration) {
        synchronized (lifecycleLock) {
            metadataRegistrations.remove(registration);
            diagnostics.setGauge(TelemetryGauge.LISTENERS, listenerCount());
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
                diagnostics.increment(TelemetryCounter.MESSAGE_PUBLISH_FAILURES);
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

    private int listenerCount() {
        return metadataRegistrations.size()
                + typedRegistrations.values().stream().mapToInt(List::size).sum();
    }

    private int typedListenerCount() {
        return typedRegistrations.values().stream().mapToInt(List::size).sum();
    }

    private void recordPublishFailure(Throwable failure) {
        diagnostics.increment(TelemetryCounter.MESSAGE_PUBLISH_FAILURES);
        diagnostics.degraded(DiagnosticComponent.TRANSPORT);
        diagnostics.report(DiagnosticCode.MESSAGE_PUBLISH_FAILED, failure);
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
