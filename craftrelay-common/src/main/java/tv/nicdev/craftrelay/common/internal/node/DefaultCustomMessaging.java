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

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.messaging.CustomMessaging;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.messaging.RequestContext;
import tv.nicdev.craftrelay.api.messaging.RequestHandler;
import tv.nicdev.craftrelay.common.internal.request.RequestHandlerRegistry;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;

final class DefaultCustomMessaging implements CustomMessaging {

    private final DefaultCraftRelayNode node;
    private final MessagingRuntime runtime;
    private final RequestHandlerRegistry requestHandlers;

    DefaultCustomMessaging(
            DefaultCraftRelayNode node,
            MessagingRuntime runtime,
            RequestHandlerRegistry requestHandlers) {
        this.node = Objects.requireNonNull(node, "node");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requestHandlers = Objects.requireNonNull(requestHandlers, "requestHandlers");
    }

    @Override
    public <M extends NetworkMessage> Subscription register(
            MessageType<M> type, MessagePayloadCodec<M> codec) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        ensureAvailable();
        try {
            return runtime.registerMessageType(type, codec);
        } catch (IllegalStateException failure) {
            throw unavailable("registering a custom message type", failure);
        }
    }

    @Override
    public <Q extends NetworkMessage, R extends NetworkMessage> Subscription handle(
            MessageType<Q> requestType,
            MessageType<R> responseType,
            RequestHandler<Q, R> handler) {
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(handler, "handler");
        if (requestType.messageClass() == responseType.messageClass()) {
            throw new IllegalArgumentException(
                    "request and response must use different message classes");
        }
        ensureAvailable();
        requireRegistered(requestType, "requestType");
        requireRegistered(responseType, "responseType");
        try {
            return requestHandlers.register(
                    requestType.messageClass(),
                    (request, context) ->
                            invokeHandler(
                                    responseType,
                                    handler,
                                    request,
                                    new RequestContext(context.sourceInstance())));
        } catch (IllegalStateException failure) {
            throw unavailable("registering a custom request handler", failure);
        }
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

    private void requireRegistered(MessageType<?> type, String argument) {
        if (!runtime.isMessageTypeRegistered(type)) {
            throw new IllegalArgumentException(
                    argument
                            + " is not registered: "
                            + type.identifier()
                            + " version "
                            + type.payloadVersion());
        }
    }

    private void ensureAvailable() {
        if (!node.isAvailable()) {
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
}
