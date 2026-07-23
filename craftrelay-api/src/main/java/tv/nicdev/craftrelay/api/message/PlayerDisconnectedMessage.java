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
