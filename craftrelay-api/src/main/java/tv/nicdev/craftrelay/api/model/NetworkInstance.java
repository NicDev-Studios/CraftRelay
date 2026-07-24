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

/**
 * Immutable point-in-time view of a network instance.
 *
 * @param id network-unique instance ID
 * @param type instance role
 * @param group optional routing group
 * @param startedAt instance startup time
 * @param lastHeartbeat latest observed heartbeat
 * @param onlinePlayerCount reported online-player count
 */
public record NetworkInstance(
        String id,
        NetworkInstanceType type,
        Optional<String> group,
        Instant startedAt,
        Instant lastHeartbeat,
        int onlinePlayerCount) {

    /**
     * Creates and validates an immutable instance snapshot.
     */
    public NetworkInstance {
        id = requireText(id, "id");
        type = Objects.requireNonNull(type, "type");
        group = Objects.requireNonNull(group, "group").map(value -> requireText(value, "group"));
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastHeartbeat = Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
        if (lastHeartbeat.isBefore(startedAt)) {
            throw new IllegalArgumentException("lastHeartbeat must not be before startedAt");
        }
        if (onlinePlayerCount < 0) {
            throw new IllegalArgumentException("onlinePlayerCount must not be negative");
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
