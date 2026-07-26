/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.state;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;

/**
 * Transport-neutral asynchronous store for globally fenced player sessions.
 *
 * <p>Implementations atomically verify the player session, proxy ID, and node lease on every
 * mutation.
 */
public interface NetworkPlayerStore extends PlayerStateProvider {

    /** Opens the shared state connection. */
    CompletableFuture<Void> connect();

    /** Claims an absent session or idempotently renews the same owned session. */
    CompletableFuture<PlayerMutationResult> claim(
            NetworkPlayer player, String nodeLeaseToken, Duration ttl);

    /** Updates the backend assignment of an owned session. */
    CompletableFuture<PlayerMutationResult> updateServer(
            UUID playerId,
            UUID sessionId,
            String proxyId,
            String nodeLeaseToken,
            Optional<String> serverId,
            Duration ttl);

    /** Removes one fully matching owned session. */
    CompletableFuture<PlayerMutationResult> release(
            UUID playerId, UUID sessionId, String proxyId, String nodeLeaseToken);

    /** Refreshes at most the supplied bounded set and returns the sessions still owned. */
    CompletableFuture<Set<PlayerSessionKey>> refresh(
            String proxyId,
            String nodeLeaseToken,
            Collection<PlayerSessionKey> sessions,
            Duration ttl);

    /** Removes stale sessions for this proxy after the new node lease has been established. */
    CompletableFuture<Collection<NetworkPlayer>> releaseStale(
            String proxyId, String nodeLeaseToken, int batchSize);

    /** Removes at most one batch of sessions owned by this node run. */
    CompletableFuture<Collection<NetworkPlayer>> releaseOwned(
            String proxyId, String nodeLeaseToken, int batchSize);

    /** Removes at most {@code batchSize} expired player-index entries. */
    CompletableFuture<Void> cleanupExpiredPlayers(int batchSize);

    /** Finds the authoritative active snapshot for one player. */
    @Override
    CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId);

    /** Closes the shared state connection and owned backend resources. */
    CompletableFuture<Void> close();
}
