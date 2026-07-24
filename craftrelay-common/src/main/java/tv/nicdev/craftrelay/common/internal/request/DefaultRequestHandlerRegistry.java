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
import tv.nicdev.craftrelay.common.internal.protocol.DecodedMessage;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;

final class DefaultRequestHandlerRegistry implements RequestHandlerRegistry {

    private static final System.Logger LOGGER =
            System.getLogger(DefaultRequestHandlerRegistry.class.getName());

    private final Object lock = new Object();
    private final MessagingRuntime runtime;
    private final FutureCompletionDispatcher completionDispatcher;
    private final ListenerDispatcher handlerDispatcher =
            new ListenerDispatcher("craftrelay-request-handler-");
    private final Map<Class<? extends NetworkMessage>, HandlerRegistration> registrations =
            new HashMap<>();
    private final Subscription requestSubscription;

    private boolean closed;

    DefaultRequestHandlerRegistry(
            MessagingRuntime runtime,
            FutureCompletionDispatcher completionDispatcher) {
        this.runtime = runtime;
        this.completionDispatcher = completionDispatcher;
        requestSubscription = runtime.subscribeDecoded(this::acceptRequest);
    }

    @Override
    public <Q extends NetworkMessage, R extends NetworkMessage> Subscription register(
            Class<Q> requestType, RequestHandler<Q, R> handler) {
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(handler, "handler");

        HandlerRegistration registration;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("request-handler registry is closed");
            }
            if (registrations.containsKey(requestType)) {
                throw new IllegalArgumentException(
                        "handler already registered for " + requestType.getName());
            }
            registration = createRegistration(requestType, handler);
            registrations.put(requestType, registration);
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
                    Class<Q> requestType, RequestHandler<Q, R> handler) {
        ListenerDispatcher.DispatchLane<DecodedMessage> lane =
                handlerDispatcher.register(
                        ListenerDispatcher.DEFAULT_QUEUE_CAPACITY,
                        decoded -> invokeHandler(requestType, handler, decoded),
                        failure -> logHandlerFailure(requestType, failure),
                        () -> logOverflow(requestType));
        return new HandlerRegistration(lane);
    }

    private <Q extends NetworkMessage, R extends NetworkMessage> void invokeHandler(
            Class<Q> requestType,
            RequestHandler<Q, R> handler,
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
                                        decoded,
                                        correlationId,
                                        response,
                                        failure)));
    }

    private void completeHandler(
            Class<? extends NetworkMessage> requestType,
            DecodedMessage decoded,
            UUID correlationId,
            NetworkMessage response,
            Throwable failure) {
        if (failure != null) {
            logHandlerFailure(requestType, AsyncFailures.unwrap(failure));
            return;
        }
        if (response == null) {
            logHandlerFailure(
                    requestType,
                    new NullPointerException("request handler completed with null"));
            return;
        }
        if (response.getClass() == requestType) {
            logHandlerFailure(
                    requestType,
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
            publish =
                    Objects.requireNonNull(
                            runtime.publish(
                                    NetworkTargets.instance(decoded.sourceInstance()),
                                    response,
                                    java.util.Optional.of(correlationId)),
                            "runtime.publish()");
        } catch (RuntimeException publishFailure) {
            logHandlerFailure(requestType, publishFailure);
            return;
        }
        publish.whenComplete(
                (ignored, publishFailure) -> {
                    if (publishFailure != null) {
                        completionDispatcher.execute(
                                () ->
                                        logHandlerFailure(
                                                requestType,
                                                AsyncFailures.unwrap(publishFailure)));
                    }
                });
    }

    private void remove(
            Class<? extends NetworkMessage> requestType,
            HandlerRegistration expected) {
        synchronized (lock) {
            registrations.remove(requestType, expected);
        }
        expected.close();
    }

    private static void logHandlerFailure(
            Class<? extends NetworkMessage> requestType, Throwable failure) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Request handler {0} failed: {1}",
                requestType.getName(),
                failure.getMessage());
    }

    private static void logOverflow(Class<? extends NetworkMessage> requestType) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Dropping request for slow handler {0}; queue limit is {1}",
                requestType.getName(),
                ListenerDispatcher.DEFAULT_QUEUE_CAPACITY);
    }

    private record HandlerRegistration(
            ListenerDispatcher.DispatchLane<DecodedMessage> lane) {

        private void dispatch(DecodedMessage decoded) {
            lane.dispatch(decoded);
        }

        private void close() {
            lane.close();
        }
    }
}
