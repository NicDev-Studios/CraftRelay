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
package tv.nicdev.craftrelay.platform.velocity;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe local session index with exact-session removal. */
final class LocalPlayerSessions {

    private final ConcurrentMap<UUID, UUID> sessions = new ConcurrentHashMap<>();

    void set(UUID playerId, UUID sessionId) {
        sessions.put(playerId, sessionId);
    }

    Optional<UUID> find(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    Optional<UUID> remove(UUID playerId) {
        return Optional.ofNullable(sessions.remove(playerId));
    }

    boolean remove(UUID playerId, UUID sessionId) {
        return sessions.remove(playerId, sessionId);
    }
}
