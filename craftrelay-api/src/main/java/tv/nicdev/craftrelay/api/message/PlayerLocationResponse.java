package tv.nicdev.craftrelay.api.message;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

/**
 * Answers a player-location request with the currently known presence.
 *
 * @param playerId requested player unique ID
 * @param player player presence when known
 */
public record PlayerLocationResponse(UUID playerId, Optional<NetworkPlayer> player)
        implements NetworkMessage {

    /** Creates a validated player-location response. */
    public PlayerLocationResponse {
        playerId = Objects.requireNonNull(playerId, "playerId");
        player = Objects.requireNonNull(player, "player");
        if (player.isPresent() && !player.get().uniqueId().equals(playerId)) {
            throw new IllegalArgumentException("player unique ID must match playerId");
        }
    }
}
