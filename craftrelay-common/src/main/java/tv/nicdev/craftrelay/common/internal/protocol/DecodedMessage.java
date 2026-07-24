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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

/**
 * Validated message data exposed across the internal protocol boundary.
 *
 * <p>Jackson tree types and the concrete wire envelope deliberately remain hidden inside the
 * protocol package.
 *
 * @param messageId unique wire-message ID
 * @param sourceInstance source instance ID
 * @param target routing target
 * @param createdAt envelope creation time
 * @param correlationId optional request correlation ID
 * @param message decoded message
 */
public record DecodedMessage(
        UUID messageId,
        String sourceInstance,
        NetworkTarget target,
        Instant createdAt,
        Optional<UUID> correlationId,
        NetworkMessage message) {

    /** Creates validated decoded message data. */
    public DecodedMessage {
        messageId = Objects.requireNonNull(messageId, "messageId");
        sourceInstance = requireText(sourceInstance, "sourceInstance");
        target = Objects.requireNonNull(target, "target");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        message = Objects.requireNonNull(message, "message");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
