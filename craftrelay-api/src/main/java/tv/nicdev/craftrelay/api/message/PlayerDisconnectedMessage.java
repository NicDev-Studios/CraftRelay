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
package tv.nicdev.craftrelay.api.message;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Announces the end of a player session.
 *
 * @param playerId player unique ID
 * @param sessionId session being disconnected
 * @param proxyId proxy that owned the session
 * @param disconnectedAt disconnection time
 */
public record PlayerDisconnectedMessage(
        UUID playerId, UUID sessionId, String proxyId, Instant disconnectedAt)
        implements NetworkMessage {

    /** Creates a validated player-disconnected message. */
    public PlayerDisconnectedMessage {
        playerId = Objects.requireNonNull(playerId, "playerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        proxyId = MessageValidation.requireText(proxyId, "proxyId");
        disconnectedAt = MessageValidation.requireInstant(disconnectedAt, "disconnectedAt");
    }
}
