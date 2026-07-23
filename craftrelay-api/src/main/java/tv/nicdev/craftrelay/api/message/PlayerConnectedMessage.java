package tv.nicdev.craftrelay.api.message;

import java.util.Objects;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

/**
 * Announces a new player session.
 *
 * @param player immutable player-presence snapshot
 */
public record PlayerConnectedMessage(NetworkPlayer player) implements NetworkMessage {

    /** Creates a validated player-connected message. */
    public PlayerConnectedMessage {
        player = Objects.requireNonNull(player, "player");
    }
}
