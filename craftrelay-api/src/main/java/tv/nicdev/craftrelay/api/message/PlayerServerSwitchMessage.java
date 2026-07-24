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
import java.util.Optional;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Announces that a player changed backend servers.
 *
 * @param playerId player unique ID
 * @param sessionId current player session
 * @param proxyId proxy handling the player
 * @param previousServerId previous server, if the player had one
 * @param serverId newly connected server
 * @param switchedAt switch time
 */
public record PlayerServerSwitchMessage(
        UUID playerId,
        UUID sessionId,
        String proxyId,
        Optional<String> previousServerId,
        String serverId,
        Instant switchedAt)
        implements NetworkMessage {

    /** Creates a validated server-switch message. */
    public PlayerServerSwitchMessage {
        playerId = Objects.requireNonNull(playerId, "playerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        proxyId = MessageValidation.requireText(proxyId, "proxyId");
        previousServerId =
                Objects.requireNonNull(previousServerId, "previousServerId")
                        .map(value -> MessageValidation.requireText(value, "previousServerId"));
        serverId = MessageValidation.requireText(serverId, "serverId");
        switchedAt = MessageValidation.requireInstant(switchedAt, "switchedAt");
    }
}
