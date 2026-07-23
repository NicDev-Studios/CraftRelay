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
