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
package tv.nicdev.craftrelay.common.internal.node;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.messaging.CustomMessaging;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageRegistration;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.messaging.RequestContext;
import tv.nicdev.craftrelay.api.messaging.RequestHandler;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.request.RequestHandlerRegistry;
import tv.nicdev.craftrelay.common.internal.observability.NodeDiagnostics;
import tv.nicdev.craftrelay.common.internal.observability.TelemetryGauge;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingCapacityConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.runtime.RuntimeMessageRegistration;

/**
 * Single lifecycle coordinator for public custom-message types and their dependent handlers.
 */
final class DefaultCustomMessaging implements CustomMessaging, AutoCloseable {

    private final Object lock = new Object();
    private final DefaultCraftRelayNode node;
    private final MessagingRuntime runtime;
    private final RequestHandlerRegistry requestHandlers;
    private final MessagingCapacityConfig capacities;
    private final NodeDiagnostics diagnostics;
    private final Map<DefaultMessageRegistration<?>, Set<HandlerLink>> registrations =
            new IdentityHashMap<>();

    private boolean closed;
    private int pendingRegistrations;
    private int activeHandlers;

    DefaultCustomMessaging(
            DefaultCraftRelayNode node,
            MessagingRuntime runtime,
            RequestHandlerRegistry requestHandlers,
            MessagingCapacityConfig capacities,
            NodeDiagnostics diagnostics) {
        this.node = Objects.requireNonNull(node, "node");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requestHandlers = Objects.requireNonNull(requestHandlers, "requestHandlers");
        this.capacities = Objects.requireNonNull(capacities, "capacities");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public <M extends NetworkMessage> MessageRegistration<M> register(
            MessageType<M> type, MessagePayloadCodec<M> codec) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        ensureAvailable();

        synchronized (lock) {
            ensureAvailableLocked();
            if (registrations.size() + pendingRegistrations
                    >= capacities.maximumCustomRegistrations()) {
                throw unavailable("registering a custom message type at capacity", null);
            }
            pendingRegistrations++;
        }
        RuntimeMessageRegistration<M> runtimeRegistration;
        try {
            runtimeRegistration = runtime.registerMessageType(type, codec);
        } catch (RuntimeException failure) {
            synchronized (lock) {
                pendingRegistrations--;
            }
            if (failure instanceof IllegalStateException) {
                throw unavailable("registering a custom message type", failure);
            }
            throw failure;
        }
        DefaultMessageRegistration<M> registration =
                new DefaultMessageRegistration<>(this, runtimeRegistration);
        synchronized (lock) {
            pendingRegistrations--;
            if (closed || !node.isAvailable()) {
                runtimeRegistration.close();
                throw unavailable("registering a custom message type", null);
            }
            registrations.put(registration, newIdentitySet());
            diagnostics.setGauge(
                    TelemetryGauge.CUSTOM_REGISTRATIONS, registrations.size());
        }
        return registration;
    }

    @Override
    public <Q extends NetworkMessage, R extends NetworkMessage> Subscription handle(
            MessageRegistration<Q> request,
            MessageRegistration<R> response,
            RequestHandler<Q, R> handler) {
        Objects.requireNonNull(handler, "handler");
        DefaultMessageRegistration<Q> ownedRequest =
                requireOwned(request, "request");
        DefaultMessageRegistration<R> ownedResponse =
                requireOwned(response, "response");
        if (ownedRequest.type().messageClass() == ownedResponse.type().messageClass()) {
            throw new IllegalArgumentException(
                    "request and response must use different message classes");
        }

        synchronized (lock) {
            ensureAvailableLocked();
            Set<HandlerLink> requestDependencies =
                    requireActiveLocked(ownedRequest, "request");
            Set<HandlerLink> responseDependencies =
                    requireActiveLocked(ownedResponse, "response");
            if (activeHandlers >= capacities.maximumCustomRequestHandlers()) {
                throw unavailable("registering a custom request handler at capacity", null);
            }
            Subscription internal;
            try {
                internal = requestHandlers.register(
                        ownedRequest.type().messageClass(),
                        (message, context) ->
                                invokeHandler(
                                        ownedResponse.type(),
                                        handler,
                                        message,
                                        new RequestContext(context.sourceInstance())),
                        (targetInstance, message, correlationId) ->
                                runtime.publish(
                                        ownedResponse.runtimeRegistration(),
                                        NetworkTargets.instance(targetInstance),
                                        message,
                                        java.util.Optional.of(correlationId)));
            } catch (IllegalStateException failure) {
                throw unavailable("registering a custom request handler", failure);
            }

            HandlerLink link =
                    new HandlerLink(internal, ownedRequest, ownedResponse);
            Subscription external =
                    Subscription.create(() -> closeHandler(link));
            link.external(external);
            requestDependencies.add(link);
            responseDependencies.add(link);
            activeHandlers++;
            return external;
        }
    }

    @Override
    public void close() {
        List<Subscription> handlers;
        List<RuntimeMessageRegistration<?>> messageTypes;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            handlers = registrations.values().stream()
                    .flatMap(Set::stream)
                    .distinct()
                    .map(HandlerLink::external)
                    .toList();
            messageTypes = new ArrayList<>(registrations.size());
            registrations.keySet().forEach(
                    registration ->
                            messageTypes.add(registration.runtimeRegistration()));
            registrations.clear();
            activeHandlers = 0;
            diagnostics.setGauge(TelemetryGauge.CUSTOM_REGISTRATIONS, 0);
        }
        handlers.forEach(Subscription::close);
        messageTypes.forEach(RuntimeMessageRegistration::close);
    }

    private <Q extends NetworkMessage, R extends NetworkMessage> CompletionStage<? extends R>
            invokeHandler(
                    MessageType<R> responseType,
                    RequestHandler<Q, R> handler,
                    Q request,
                    RequestContext context) {
        CompletionStage<? extends R> response =
                Objects.requireNonNull(
                        handler.handle(request, context),
                        "custom request handler result");
        return response.thenApply(value -> {
            R checked = Objects.requireNonNull(value, "custom request handler response");
            if (checked.getClass() != responseType.messageClass()) {
                throw new IllegalArgumentException(
                        "custom request handler returned "
                                + checked.getClass().getName()
                                + " instead of "
                                + responseType.messageClass().getName());
            }
            return checked;
        });
    }

    private void closeRegistration(DefaultMessageRegistration<?> registration) {
        List<Subscription> handlers;
        synchronized (lock) {
            Set<HandlerLink> dependencies = registrations.remove(registration);
            if (dependencies == null) {
                return;
            }
            handlers = dependencies.stream()
                    .map(HandlerLink::external)
                    .toList();
            diagnostics.setGauge(
                    TelemetryGauge.CUSTOM_REGISTRATIONS, registrations.size());
        }
        handlers.forEach(Subscription::close);
        registration.runtimeRegistration().close();
    }

    private void closeHandler(HandlerLink link) {
        boolean removed = false;
        synchronized (lock) {
            Set<HandlerLink> requestDependencies =
                    registrations.get(link.request());
            if (requestDependencies != null) {
                removed = requestDependencies.remove(link);
            }
            Set<HandlerLink> responseDependencies =
                    registrations.get(link.response());
            if (responseDependencies != null) {
                removed |= responseDependencies.remove(link);
            }
            if (removed) {
                activeHandlers--;
            }
        }
        link.internal().close();
    }

    private <M extends NetworkMessage> DefaultMessageRegistration<M> requireOwned(
            MessageRegistration<M> registration, String argument) {
        Objects.requireNonNull(registration, argument);
        if (!(registration instanceof DefaultMessageRegistration<?> candidate)
                || candidate.owner() != this) {
            throw new IllegalArgumentException(
                    argument + " registration belongs to another CraftRelay API");
        }
        @SuppressWarnings("unchecked")
        DefaultMessageRegistration<M> owned =
                (DefaultMessageRegistration<M>) candidate;
        return owned;
    }

    private Set<HandlerLink> requireActiveLocked(
            DefaultMessageRegistration<?> registration, String argument) {
        Set<HandlerLink> dependencies = registrations.get(registration);
        if (dependencies == null || registration.runtimeRegistration().isClosed()) {
            throw new IllegalArgumentException(argument + " registration is closed");
        }
        return dependencies;
    }

    private boolean isActive(DefaultMessageRegistration<?> registration) {
        synchronized (lock) {
            return !closed
                    && registrations.containsKey(registration)
                    && !registration.runtimeRegistration().isClosed();
        }
    }

    private void ensureAvailable() {
        if (!node.isAvailable()) {
            throw unavailable("using custom messaging", null);
        }
    }

    private void ensureAvailableLocked() {
        if (closed || !node.isAvailable()) {
            throw unavailable("using custom messaging", null);
        }
    }

    private ApiUnavailableException unavailable(String operation, Throwable cause) {
        String message =
                "CraftRelay API stopped while " + operation + "; state is " + node.apiState();
        return cause == null
                ? new ApiUnavailableException(message)
                : new ApiUnavailableException(message, cause);
    }

    private static Set<HandlerLink> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final class DefaultMessageRegistration<M extends NetworkMessage>
            implements MessageRegistration<M> {

        private final DefaultCustomMessaging owner;
        private final RuntimeMessageRegistration<M> runtimeRegistration;

        private DefaultMessageRegistration(
                DefaultCustomMessaging owner,
                RuntimeMessageRegistration<M> runtimeRegistration) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.runtimeRegistration =
                    Objects.requireNonNull(runtimeRegistration, "runtimeRegistration");
        }

        @Override
        public MessageType<M> type() {
            return runtimeRegistration.type();
        }

        @Override
        public boolean isClosed() {
            return !owner.isActive(this);
        }

        @Override
        public void close() {
            owner.closeRegistration(this);
        }

        private DefaultCustomMessaging owner() {
            return owner;
        }

        private RuntimeMessageRegistration<M> runtimeRegistration() {
            return runtimeRegistration;
        }
    }

    private static final class HandlerLink {

        private final Subscription internal;
        private final DefaultMessageRegistration<?> request;
        private final DefaultMessageRegistration<?> response;
        private Subscription external;

        private HandlerLink(
                Subscription internal,
                DefaultMessageRegistration<?> request,
                DefaultMessageRegistration<?> response) {
            this.internal = Objects.requireNonNull(internal, "internal");
            this.request = Objects.requireNonNull(request, "request");
            this.response = Objects.requireNonNull(response, "response");
        }

        private Subscription internal() {
            return internal;
        }

        private DefaultMessageRegistration<?> request() {
            return request;
        }

        private DefaultMessageRegistration<?> response() {
            return response;
        }

        private Subscription external() {
            return Objects.requireNonNull(external, "external");
        }

        private void external(Subscription external) {
            if (this.external != null) {
                throw new IllegalStateException("external subscription is already assigned");
            }
            this.external = Objects.requireNonNull(external, "external");
        }
    }
}
