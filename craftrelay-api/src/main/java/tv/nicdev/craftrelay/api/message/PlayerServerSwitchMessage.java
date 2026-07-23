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
