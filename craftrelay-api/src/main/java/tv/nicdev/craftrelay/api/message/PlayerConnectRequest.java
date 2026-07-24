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

import java.util.Objects;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Requests that a player be connected to a backend server.
 *
 * @param playerId player unique ID
 * @param serverId destination server ID
 */
public record PlayerConnectRequest(UUID playerId, String serverId) implements NetworkMessage {

    /** Creates a validated player-connect request. */
    public PlayerConnectRequest {
        playerId = Objects.requireNonNull(playerId, "playerId");
        serverId = MessageValidation.requireText(serverId, "serverId");
    }
}
