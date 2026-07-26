/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.state;

import java.util.Objects;
import java.util.UUID;

/** Immutable identity of one player session used by bounded refresh operations. */
public record PlayerSessionKey(UUID playerId, UUID sessionId) {

    /** Creates a validated session key. */
    public PlayerSessionKey {
        playerId = Objects.requireNonNull(playerId, "playerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }
}
