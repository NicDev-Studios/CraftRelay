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
package tv.nicdev.craftrelay.common.internal.request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.internal.concurrent.ListenerDispatcher;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticCode;
import tv.nicdev.craftrelay.common.internal.observability.NodeDiagnostics;
import tv.nicdev.craftrelay.common.internal.observability.TelemetryGauge;
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingCapacityConfig;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;

final class DefaultRequestHandlerRegistry implements RequestHandlerRegistry {

    private final Object lock = new Object();
    private final MessagingRuntime runtime;
    private final FutureCompletionDispatcher completionDispatcher;
    private final ListenerDispatcher handlerDispatcher;
    private final NodeDiagnostics diagnostics;
    private final MessagingCapacityConfig capacities;
    private final Map<Class<? extends NetworkMessage>, HandlerRegistration> registrations =
            new HashMap<>();
    private final Subscription requestSubscription;

    private boolean closed;

    DefaultRequestHandlerRegistry(
            MessagingRuntime runtime,
            FutureCompletionDispatcher completionDispatcher) {
        this(
                runtime,
                completionDispatcher,
                new NodeDiagnostics(),
                MessagingCapacityConfig.defaults());
    }

    DefaultRequestHandlerRegistry(
            MessagingRuntime runtime,
            FutureCompletionDispatcher completionDispatcher,
            NodeDiagnostics diagnostics,
            MessagingCapacityConfig capacities) {
        this.runtime = runtime;
        this.completionDispatcher = completionDispatcher;
        this.diagnostics = diagnostics;
        this.capacities = capacities;
        handlerDispatcher =
                new ListenerDispatcher("craftrelay-request-handler-", diagnostics);
        requestSubscription = runtime.subscribeDecoded(this::acceptRequest);
    }

    @Override
    public <Q extends NetworkMessage, R extends NetworkMessage> Subscription register(
            Class<Q> requestType, RequestHandler<Q, R> handler) {
        return register(
                requestType,
                handler,
                (targetInstance, response, correlationId) ->
                        runtime.publish(
                                NetworkTargets.instance(targetInstance),
                                response,
                                java.util.Optional.of(correlationId)));
    }

    @Override
    public <Q extends NetworkMessage, R extends NetworkMessage> Subscription register(
            Class<Q> requestType,
            RequestHandler<Q, R> handler,
            ResponsePublisher<R> responsePublisher) {
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(responsePublisher, "responsePublisher");

        HandlerRegistration registration;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("request-handler registry is closed");
            }
            if (registrations.containsKey(requestType)) {
                throw new IllegalArgumentException(
                        "handler already registered for " + requestType.getName());
            }
            registration = createRegistration(
                    requestType, handler, responsePublisher);
            registrations.put(requestType, registration);
            diagnostics.setGauge(TelemetryGauge.REQUEST_HANDLERS, registrations.size());
        }
        HandlerRegistration captured = registration;
        return Subscription.create(() -> remove(requestType, captured));
    }

    @Override
    public void close() {
        List<HandlerRegistration> removed;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            removed = new ArrayList<>(registrations.values());
            registrations.clear();
            diagnostics.setGauge(TelemetryGauge.REQUEST_HANDLERS, 0);
        }
        requestSubscription.close();
        removed.forEach(HandlerRegistration::close);
        handlerDispatcher.close();
    }

    private void acceptRequest(DecodedMessage decoded) {
        if (decoded.correlationId().isEmpty()) {
            return;
        }
        HandlerRegistration registration;
        synchronized (lock) {
            if (closed) {
                return;
            }
            registration = registrations.get(decoded.message().getClass());
        }
        if (registration != null) {
            registration.dispatch(decoded);
        }
    }

    private <Q extends NetworkMessage, R extends NetworkMessage>
            HandlerRegistration createRegistration(
                    Class<Q> requestType,
                    RequestHandler<Q, R> handler,
                    ResponsePublisher<R> responsePublisher) {
        ListenerDispatcher.DispatchLane<DecodedMessage> lane =
                handlerDispatcher.register(
                        capacities.dispatchQueueCapacity(),
                        decoded ->
                                invokeHandler(
                                        requestType,
                                        handler,
                                        responsePublisher,
                                        decoded),
                        failure -> diagnostics.report(DiagnosticCode.HANDLER_FAILED, failure),
                        () -> {});
        return new HandlerRegistration(lane);
    }

    private <Q extends NetworkMessage, R extends NetworkMessage> void invokeHandler(
            Class<Q> requestType,
            RequestHandler<Q, R> handler,
            ResponsePublisher<R> responsePublisher,
            DecodedMessage decoded) {
        UUID correlationId = decoded.correlationId().orElseThrow();
        RequestContext context =
                new RequestContext(decoded.sourceInstance(), correlationId);
        CompletionStage<? extends R> responseStage =
                Objects.requireNonNull(
                        handler.handle(requestType.cast(decoded.message()), context),
                        "request handler result");
        responseStage.whenComplete(
                (response, failure) ->
                        completionDispatcher.execute(
                                () -> completeHandler(
                                        requestType,
                                        responsePublisher,
                                        decoded,
                                        correlationId,
                                        response,
                                        failure)));
    }

    private <R extends NetworkMessage> void completeHandler(
            Class<? extends NetworkMessage> requestType,
            ResponsePublisher<R> responsePublisher,
            DecodedMessage decoded,
            UUID correlationId,
            R response,
            Throwable failure) {
        if (failure != null) {
            diagnostics.report(DiagnosticCode.HANDLER_FAILED, AsyncFailures.unwrap(failure));
            return;
        }
        if (response == null) {
            diagnostics.report(
                    DiagnosticCode.HANDLER_FAILED,
                    new NullPointerException("request handler completed with null"));
            return;
        }
        if (response.getClass() == requestType) {
            diagnostics.report(
                    DiagnosticCode.HANDLER_FAILED,
                    new IllegalArgumentException(
                            "request and response must use different message types"));
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
        }

        CompletableFuture<Void> publish;
        try {
            publish = Objects.requireNonNull(
                    responsePublisher.publish(
                            decoded.sourceInstance(), response, correlationId),
                    "responsePublisher.publish()");
        } catch (RuntimeException publishFailure) {
            diagnostics.report(DiagnosticCode.HANDLER_FAILED, publishFailure);
            return;
        }
        publish.whenComplete(
                (ignored, publishFailure) -> {
                    if (publishFailure != null) {
                        completionDispatcher.execute(
                                () ->
                                        diagnostics.report(
                                                DiagnosticCode.HANDLER_FAILED,
                                                AsyncFailures.unwrap(publishFailure)));
                    }
                });
    }

    private void remove(
            Class<? extends NetworkMessage> requestType,
            HandlerRegistration expected) {
        synchronized (lock) {
            registrations.remove(requestType, expected);
            diagnostics.setGauge(TelemetryGauge.REQUEST_HANDLERS, registrations.size());
        }
        expected.closeAfterDrain();
    }

    private record HandlerRegistration(
            ListenerDispatcher.DispatchLane<DecodedMessage> lane) {

        private void dispatch(DecodedMessage decoded) {
            lane.dispatch(decoded);
        }

        private void close() {
            lane.close();
        }

        private void closeAfterDrain() {
            lane.closeAfterDrain();
        }
    }
}
