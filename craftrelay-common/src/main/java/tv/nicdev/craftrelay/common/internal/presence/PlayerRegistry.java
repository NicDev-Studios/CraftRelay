/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.PlayerConnectedMessage;
import tv.nicdev.craftrelay.api.message.PlayerDisconnectedMessage;
import tv.nicdev.craftrelay.api.message.PlayerServerSwitchMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;
import tv.nicdev.craftrelay.common.internal.state.NetworkPlayerStore;
import tv.nicdev.craftrelay.common.internal.state.PlayerMutationResult;
import tv.nicdev.craftrelay.common.internal.state.PlayerMutationStatus;
import tv.nicdev.craftrelay.common.internal.state.PlayerSessionKey;
import tv.nicdev.craftrelay.common.internal.state.PlayerStateProvider;

/**
 * Lifecycle-safe local owner of distributed player sessions.
 *
 * <p>Mutations are serialized per player without holding locks across store I/O. Different players
 * remain independent, and refresh attempts never overlap.
 */
public final class PlayerRegistry implements PlayerPresence, PlayerStateProvider {

    private static final System.Logger LOGGER =
            System.getLogger(PlayerRegistry.class.getName());

    private final Object lifecycleLock = new Object();
    private final NetworkPlayerStore store;
    private final MessagingRuntime runtime;
    private final LocalInstanceIdentity identity;
    private final PlayerPresenceConfig config;
    private final NodeLease nodeLease;
    private final Consumer<? super PlayerSessionKey> ownershipLossHandler;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, NetworkPlayer> localPlayers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> mutationLanes =
            new ConcurrentHashMap<>();
    private final ExecutorService mutationExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("craftrelay-player-mutation-", 0).factory());
    private final ScheduledExecutorService refreshExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform()
                            .daemon()
                            .name("craftrelay-player-presence-")
                            .factory());

    private RegistryState state = RegistryState.NEW;
    private CompletableFuture<Void> startFuture;
    private CompletableFuture<Void> stopFuture;
    private ScheduledFuture<?> scheduledRefresh;
    private CompletableFuture<Void> refreshFuture = CompletableFuture.completedFuture(null);

    /** Creates a registry using the system UTC clock. */
    public PlayerRegistry(
            NetworkPlayerStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            PlayerPresenceConfig config,
            NodeLease nodeLease,
            Consumer<? super PlayerSessionKey> ownershipLossHandler) {
        this(
                store,
                runtime,
                identity,
                config,
                nodeLease,
                ownershipLossHandler,
                Clock.systemUTC());
    }

    PlayerRegistry(
            NetworkPlayerStore store,
            MessagingRuntime runtime,
            LocalInstanceIdentity identity,
            PlayerPresenceConfig config,
            NodeLease nodeLease,
            Consumer<? super PlayerSessionKey> ownershipLossHandler,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.config = Objects.requireNonNull(config, "config");
        this.nodeLease = Objects.requireNonNull(nodeLease, "nodeLease");
        this.ownershipLossHandler =
                Objects.requireNonNull(ownershipLossHandler, "ownershipLossHandler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Cleans safely fenced stale sessions and starts periodic refreshes on proxy nodes. */
    public CompletableFuture<Void> start() {
        synchronized (lifecycleLock) {
            if (state == RegistryState.RUNNING || state == RegistryState.STARTING) {
                return startFuture;
            }
            if (state == RegistryState.STOPPING || state == RegistryState.STOPPED) {
                return unavailable("Player registry is stopping or stopped");
            }
            state = RegistryState.STARTING;
            CompletableFuture<Void> attempt;
            try {
                attempt = identity.instanceType() == NetworkInstanceType.PROXY
                        ? cleanupStaleSessions()
                        : CompletableFuture.completedFuture(null);
            } catch (RuntimeException failure) {
                attempt = CompletableFuture.failedFuture(failure);
            }
            startFuture = attempt.handleAsync((ignored, failure) -> {
                synchronized (lifecycleLock) {
                    if (failure == null && state == RegistryState.STARTING) {
                        state = RegistryState.RUNNING;
                        if (identity.instanceType() == NetworkInstanceType.PROXY) {
                            scheduleRefresh();
                        }
                        return null;
                    }
                    if (state == RegistryState.STARTING) {
                        state = RegistryState.NEW;
                    }
                }
                if (failure != null) {
                    throw new java.util.concurrent.CompletionException(
                            AsyncFailures.unwrap(failure));
                }
                throw new java.util.concurrent.CompletionException(
                        new ApiUnavailableException("Player registry stopped during start"));
            }, mutationExecutor);
            return startFuture;
        }
    }

    @Override
    public CompletableFuture<NetworkPlayer> connect(
            UUID playerId, String username, UUID sessionId, Optional<String> serverId) {
        UUID validatedPlayerId = Objects.requireNonNull(playerId, "playerId");
        String validatedUsername = requireText(username, "username");
        UUID validatedSessionId = Objects.requireNonNull(sessionId, "sessionId");
        Optional<String> validatedServer =
                Objects.requireNonNull(serverId, "serverId")
                        .map(value -> requireText(value, "serverId"));
        return acceptedMutation(
                validatedPlayerId,
                () -> claim(
                        validatedPlayerId,
                        validatedUsername,
                        validatedSessionId,
                        validatedServer));
    }

    @Override
    public CompletableFuture<NetworkPlayer> switchServer(
            UUID playerId, UUID sessionId, String serverId) {
        UUID validatedPlayerId = Objects.requireNonNull(playerId, "playerId");
        UUID validatedSessionId = Objects.requireNonNull(sessionId, "sessionId");
        String validatedServerId = requireText(serverId, "serverId");
        return acceptedMutation(
                validatedPlayerId,
                () -> updateServer(
                        validatedPlayerId, validatedSessionId, validatedServerId));
    }

    @Override
    public CompletableFuture<Boolean> disconnect(UUID playerId, UUID sessionId) {
        UUID validatedPlayerId = Objects.requireNonNull(playerId, "playerId");
        UUID validatedSessionId = Objects.requireNonNull(sessionId, "sessionId");
        return acceptedMutation(
                validatedPlayerId,
                () -> release(validatedPlayerId, validatedSessionId));
    }

    @Override
    public int onlinePlayerCount() {
        return localPlayers.size();
    }

    @Override
    public CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
        return store.player(Objects.requireNonNull(playerId, "playerId"));
    }

    /**
     * Stops refreshes, drains accepted mutations, and releases every session still owned.
     */
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> inFlight;
        boolean releaseSessions;
        synchronized (lifecycleLock) {
            if (state == RegistryState.STOPPING || state == RegistryState.STOPPED) {
                return stopFuture;
            }
            RegistryState previousState = state;
            state = RegistryState.STOPPING;
            if (scheduledRefresh != null) {
                scheduledRefresh.cancel(false);
                scheduledRefresh = null;
            }
            CompletableFuture<Void> starting = startFuture == null
                    ? CompletableFuture.completedFuture(null)
                    : startFuture.handle((ignored, failure) -> (Void) null);
            inFlight = CompletableFuture.allOf(
                    starting,
                    refreshFuture.handle((ignored, failure) -> (Void) null));
            releaseSessions = previousState != RegistryState.NEW;
            stopFuture = inFlight
                    .handle((ignored, failure) -> (Void) null)
                    .thenComposeAsync(ignored -> drainMutations(), mutationExecutor)
                    .thenComposeAsync(
                            ignored -> releaseSessions
                                            && identity.instanceType()
                                                    == NetworkInstanceType.PROXY
                                    ? releaseOwnedSessions()
                                    : CompletableFuture.completedFuture(null),
                            mutationExecutor)
                    .whenComplete((ignored, failure) -> {
                        localPlayers.clear();
                        mutationExecutor.shutdown();
                        refreshExecutor.shutdown();
                        synchronized (lifecycleLock) {
                            state = RegistryState.STOPPED;
                        }
                    });
            return stopFuture;
        }
    }

    private CompletableFuture<NetworkPlayer> claim(
            UUID playerId,
            String username,
            UUID sessionId,
            Optional<String> serverId) {
        Instant now = clock.instant();
        NetworkPlayer candidate = new NetworkPlayer(
                playerId,
                username,
                identity.instanceId(),
                serverId,
                sessionId,
                now,
                now);
        return store.claim(candidate, nodeLease.token(), config.playerTtl())
                .thenComposeAsync(result -> {
                    if (result.status() == PlayerMutationStatus.CONFLICT) {
                        return CompletableFuture.failedFuture(
                                new PlayerSessionConflictException(
                                        "Player " + playerId + " already has an active session"));
                    }
                    if (result.status() == PlayerMutationStatus.OWNERSHIP_LOST) {
                        return unavailable("Local proxy lease no longer owns player sessions");
                    }
                    NetworkPlayer claimed = result.current().orElseThrow(
                            () -> new IllegalStateException("Claim did not return a player"));
                    localPlayers.put(playerId, claimed);
                    return publishBestEffort(new PlayerConnectedMessage(claimed))
                            .thenApply(ignored -> claimed);
                }, mutationExecutor);
    }

    private CompletableFuture<NetworkPlayer> updateServer(
            UUID playerId, UUID sessionId, String serverId) {
        return store.updateServer(
                        playerId,
                        sessionId,
                        identity.instanceId(),
                        nodeLease.token(),
                        Optional.of(serverId),
                        config.playerTtl())
                .thenComposeAsync(result -> {
                    if (result.status() != PlayerMutationStatus.APPLIED) {
                        loseLocalSession(new PlayerSessionKey(playerId, sessionId));
                        return unavailable("Player session ownership was lost");
                    }
                    NetworkPlayer updated = result.current().orElseThrow(
                            () -> new IllegalStateException("Server update did not return a player"));
                    localPlayers.put(playerId, updated);
                    Optional<String> previousServer = result.previous()
                            .flatMap(NetworkPlayer::serverId);
                    return publishBestEffort(new PlayerServerSwitchMessage(
                                    playerId,
                                    sessionId,
                                    identity.instanceId(),
                                    previousServer,
                                    serverId,
                                    updated.lastUpdatedAt()))
                            .thenApply(ignored -> updated);
                }, mutationExecutor);
    }

    private CompletableFuture<Boolean> release(UUID playerId, UUID sessionId) {
        return store.release(
                        playerId, sessionId, identity.instanceId(), nodeLease.token())
                .thenComposeAsync(result -> {
                    if (result.status() != PlayerMutationStatus.APPLIED) {
                        loseLocalSession(new PlayerSessionKey(playerId, sessionId));
                        return CompletableFuture.completedFuture(false);
                    }
                    NetworkPlayer removed = result.previous().orElseThrow(
                            () -> new IllegalStateException("Release did not return a player"));
                    localPlayers.computeIfPresent(
                            playerId,
                            (ignored, current) ->
                                    current.sessionId().equals(sessionId) ? null : current);
                    return publishDisconnect(removed).thenApply(ignored -> true);
                }, mutationExecutor);
    }

    private <T> CompletableFuture<T> acceptedMutation(
            UUID playerId, Supplier<CompletableFuture<T>> action) {
        synchronized (lifecycleLock) {
            if (state != RegistryState.RUNNING) {
                return unavailable("Player registry is not running");
            }
            if (identity.instanceType() != NetworkInstanceType.PROXY) {
                return unavailable("Only proxy nodes may mutate player presence");
            }
            return enqueue(playerId, action);
        }
    }

    private <T> CompletableFuture<T> enqueue(
            UUID playerId, Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        mutationLanes.compute(playerId, (ignored, previous) -> {
            CompletableFuture<Void> predecessor =
                    previous == null ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<Void> next = predecessor
                    .handleAsync((value, failure) -> null, mutationExecutor)
                    .thenComposeAsync(
                            value -> {
                                try {
                                    return Objects.requireNonNull(action.get(), "mutation future");
                                } catch (RuntimeException failure) {
                                    return CompletableFuture.failedFuture(failure);
                                }
                            },
                            mutationExecutor)
                    .handleAsync((value, failure) -> {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(AsyncFailures.unwrap(failure));
                        }
                        return null;
                    }, mutationExecutor);
            next.whenCompleteAsync(
                    (value, failure) -> mutationLanes.remove(playerId, next),
                    mutationExecutor);
            return next;
        });
        return result;
    }

    private void scheduleRefresh() {
        synchronized (lifecycleLock) {
            if (state != RegistryState.RUNNING) {
                return;
            }
            scheduledRefresh = refreshExecutor.schedule(
                    this::beginRefresh,
                    config.refreshInterval().toNanos(),
                    TimeUnit.NANOSECONDS);
        }
    }

    private void beginRefresh() {
        CompletableFuture<Void> operation;
        synchronized (lifecycleLock) {
            if (state != RegistryState.RUNNING) {
                return;
            }
            List<PlayerSessionKey> sessions = localPlayers.values().stream()
                    .map(player -> new PlayerSessionKey(player.uniqueId(), player.sessionId()))
                    .toList();
            try {
                operation = refreshBatches(sessions, 0);
            } catch (RuntimeException failure) {
                operation = CompletableFuture.failedFuture(failure);
            }
            refreshFuture = operation;
        }
        operation.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                logFailure("Could not refresh player presence; will retry", failure);
            }
            scheduleRefresh();
        }, mutationExecutor);
    }

    private CompletableFuture<Void> refreshBatches(
            List<PlayerSessionKey> sessions, int offset) {
        if (offset >= sessions.size()) {
            return store.cleanupExpiredPlayers(config.batchSize());
        }
        int end = Math.min(offset + config.batchSize(), sessions.size());
        List<PlayerSessionKey> batch = sessions.subList(offset, end);
        return store.refresh(
                        identity.instanceId(),
                        nodeLease.token(),
                        batch,
                        config.playerTtl())
                .thenComposeAsync(refreshed -> {
                    Set<PlayerSessionKey> owned = Set.copyOf(refreshed);
                    batch.stream()
                            .filter(session -> !owned.contains(session))
                            .forEach(this::loseLocalSession);
                    return refreshBatches(sessions, end);
                }, mutationExecutor);
    }

    private void loseLocalSession(PlayerSessionKey session) {
        NetworkPlayer current = localPlayers.get(session.playerId());
        if (current == null || !current.sessionId().equals(session.sessionId())) {
            return;
        }
        if (localPlayers.remove(session.playerId(), current)) {
            try {
                ownershipLossHandler.accept(session);
            } catch (Throwable failure) {
                logFailure("Player ownership-loss callback failed", failure);
            }
        }
    }

    private CompletableFuture<Void> cleanupStaleSessions() {
        return store.cleanupExpiredPlayers(config.batchSize())
                .thenComposeAsync(ignored -> releaseStaleBatch(), mutationExecutor);
    }

    private CompletableFuture<Void> releaseStaleBatch() {
        return store.releaseStale(
                        identity.instanceId(), nodeLease.token(), config.batchSize())
                .thenComposeAsync(removed -> publishDisconnects(removed)
                        .thenComposeAsync(
                                ignored -> removed.size() == config.batchSize()
                                        ? releaseStaleBatch()
                                        : CompletableFuture.completedFuture(null),
                                mutationExecutor),
                        mutationExecutor);
    }

    private CompletableFuture<Void> releaseOwnedSessions() {
        return store.releaseOwned(
                        identity.instanceId(), nodeLease.token(), config.batchSize())
                .thenComposeAsync(removed -> publishDisconnects(removed)
                        .thenComposeAsync(
                                ignored -> removed.size() == config.batchSize()
                                        ? releaseOwnedSessions()
                                        : CompletableFuture.completedFuture(null),
                                mutationExecutor),
                        mutationExecutor);
    }

    private CompletableFuture<Void> publishDisconnects(Collection<NetworkPlayer> players) {
        List<CompletableFuture<Void>> publishes = new ArrayList<>(players.size());
        for (NetworkPlayer player : players) {
            publishes.add(publishDisconnect(player));
        }
        return CompletableFuture.allOf(publishes.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> publishDisconnect(NetworkPlayer player) {
        return publishBestEffort(new PlayerDisconnectedMessage(
                player.uniqueId(),
                player.sessionId(),
                player.proxyId(),
                clock.instant()));
    }

    private CompletableFuture<Void> publishBestEffort(NetworkMessage message) {
        CompletableFuture<Void> publish;
        try {
            publish = Objects.requireNonNull(
                    runtime.publish(NetworkTargets.allInstances(), message),
                    "runtime.publish()");
        } catch (RuntimeException failure) {
            logFailure("Could not publish player-presence event", failure);
            return CompletableFuture.completedFuture(null);
        }
        return publish.handleAsync((ignored, failure) -> {
            if (failure != null) {
                logFailure("Could not publish player-presence event", failure);
            }
            return null;
        }, mutationExecutor);
    }

    private CompletableFuture<Void> drainMutations() {
        CompletableFuture<?>[] current =
                mutationLanes.values().toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(current);
    }

    private static <T> CompletableFuture<T> unavailable(String message) {
        return CompletableFuture.failedFuture(new ApiUnavailableException(message));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void logFailure(String message, Throwable failure) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                message,
                AsyncFailures.unwrap(failure));
    }

    private enum RegistryState {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
