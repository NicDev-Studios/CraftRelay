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
import tools.jackson.databind.JsonNode;
import tv.nicdev.craftrelay.api.target.NetworkTarget;

record MessageEnvelope(
        UUID messageId,
        int protocolVersion,
        String type,
        String sourceInstance,
        NetworkTarget target,
        Instant createdAt,
        Optional<UUID> correlationId,
        JsonNode payload) {

    MessageEnvelope {
        messageId = Objects.requireNonNull(messageId, "messageId");
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        type = requireText(type, "type");
        sourceInstance = requireText(sourceInstance, "sourceInstance");
        target = Objects.requireNonNull(target, "target");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        payload = Objects.requireNonNull(payload, "payload");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
