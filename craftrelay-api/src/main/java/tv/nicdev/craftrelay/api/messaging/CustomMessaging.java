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
package tv.nicdev.craftrelay.api.messaging;

import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;

/**
 * Thread-safe local allowlist for custom message types and request handlers.
 *
 * <p>Registrations are not distributed. Every participating node must register compatible types
 * before exchanging them.
 */
public interface CustomMessaging {

    /**
     * Registers one custom message type and its explicit JSON codec.
     *
     * @param type stable message identity
     * @param codec thread-safe payload codec
     * @param <M> message type
     * @return idempotently closeable registration
     * @throws IllegalArgumentException if the identifier, version, or class is already registered
     * @throws ApiUnavailableException if the local API is not available
     */
    <M extends NetworkMessage> MessageRegistration<M> register(
            MessageType<M> type, MessagePayloadCodec<M> codec);

    /**
     * Registers the only local handler for an exact custom request type.
     *
     * <p>The response is automatically correlated and sent to the requesting instance. Both types
     * must already be registered locally.
     *
     * @param request active request-type registration from this API
     * @param response active, different response-type registration from this API
     * @param handler asynchronous handler
     * @param <Q> request type
     * @param <R> response type
     * @return idempotently closeable handler registration
     * @throws IllegalArgumentException for foreign or closed registrations, equal
     *     request/response classes, or an existing handler
     * @throws ApiUnavailableException if the local API is not available
     */
    <Q extends NetworkMessage, R extends NetworkMessage> Subscription handle(
            MessageRegistration<Q> request,
            MessageRegistration<R> response,
            RequestHandler<Q, R> handler);
}
