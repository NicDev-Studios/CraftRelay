package tv.nicdev.craftrelay.api.message;

import java.util.Objects;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Requests the current network location of a player.
 *
 * @param playerId player unique ID
 */
public record PlayerLocationRequest(UUID playerId) implements NetworkMessage {

    /** Creates a validated player-location request. */
    public PlayerLocationRequest {
        playerId = Objects.requireNonNull(playerId, "playerId");
    }
}
