package tv.nicdev.craftrelay.api.message;

import tv.nicdev.craftrelay.api.NetworkMessage;

/**
 * Carries text intended for a network-wide broadcast.
 *
 * @param content non-blank broadcast content
 */
public record GlobalBroadcastMessage(String content) implements NetworkMessage {

    /** Creates a validated broadcast message. */
    public GlobalBroadcastMessage {
        content = MessageValidation.requireText(content, "content");
    }
}
