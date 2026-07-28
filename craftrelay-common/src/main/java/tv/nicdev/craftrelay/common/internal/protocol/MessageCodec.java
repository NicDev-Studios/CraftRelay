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
package tv.nicdev.craftrelay.common.internal.protocol;

import java.util.Optional;
import java.util.UUID;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.messaging.MessagePayloadCodec;
import tv.nicdev.craftrelay.api.messaging.MessageType;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

/**
 * Internal, transport-neutral codec boundary shared by CraftRelay implementation modules.
 */
public interface MessageCodec {

    /**
     * Encodes a message and its routing metadata into a new wire envelope.
     *
     * @param sourceInstance source instance ID
     * @param target routing target
     * @param message message payload
     * @param correlationId optional request correlation ID
     * @return encoded envelope
     */
    byte[] encode(
            String sourceInstance,
            NetworkTarget target,
            NetworkMessage message,
            Optional<UUID> correlationId);

    /**
     * Decodes and validates a wire envelope.
     *
     * @param encoded encoded envelope
     * @return immutable decoded metadata and message
     */
    default DecodedMessage decode(byte[] encoded) {
        return decode(prepare(encoded));
    }

    /**
     * Validates an envelope without invoking its payload codec.
     *
     * @param encoded encoded envelope
     * @return prepared message
     */
    PreparedMessage prepare(byte[] encoded);

    /**
     * Decodes a prepared payload.
     *
     * @param prepared prepared envelope
     * @return decoded message
     */
    DecodedMessage decode(PreparedMessage prepared);

    /** Registers one explicit custom message codec. */
    <M extends NetworkMessage> Subscription register(
            MessageType<M> type, MessagePayloadCodec<M> payloadCodec);

    /** Returns whether the exact custom type is currently registered. */
    boolean isRegistered(MessageType<?> type);

    /** Removes every custom registration while preserving built-in message types. */
    void closeCustomRegistrations();
}
