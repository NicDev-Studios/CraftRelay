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
package tv.nicdev.craftrelay.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable point-in-time view of a player's network presence.
 *
 * @param uniqueId player unique ID
 * @param username last known username
 * @param proxyId ID of the connected proxy
 * @param serverId ID of the connected backend server, if any
 * @param sessionId unique ID of the current connection session
 * @param connectedAt session connection time
 * @param lastUpdatedAt latest presence update time
 */
public record NetworkPlayer(
        UUID uniqueId,
        String username,
        String proxyId,
        Optional<String> serverId,
        UUID sessionId,
        Instant connectedAt,
        Instant lastUpdatedAt) {

    /**
     * Creates and validates an immutable player snapshot.
     */
    public NetworkPlayer {
        uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
        username = requireText(username, "username");
        proxyId = requireText(proxyId, "proxyId");
        serverId =
                Objects.requireNonNull(serverId, "serverId")
                        .map(value -> requireText(value, "serverId"));
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
        if (lastUpdatedAt.isBefore(connectedAt)) {
            throw new IllegalArgumentException("lastUpdatedAt must not be before connectedAt");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
