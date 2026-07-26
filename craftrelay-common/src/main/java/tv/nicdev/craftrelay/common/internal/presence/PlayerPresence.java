/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

/** Internal platform-facing contract for local player-session mutations. */
public interface PlayerPresence {

    /** Claims a player session on the local proxy. */
    CompletableFuture<NetworkPlayer> connect(
            UUID playerId, String username, UUID sessionId, Optional<String> serverId);

    /** Changes the current backend server for a local session. */
    CompletableFuture<NetworkPlayer> switchServer(
            UUID playerId, UUID sessionId, String serverId);

    /** Releases a local session; returns whether this node still owned and removed it. */
    CompletableFuture<Boolean> disconnect(UUID playerId, UUID sessionId);

    /** Returns the local player count without I/O. */
    int onlinePlayerCount();
}
