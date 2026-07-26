/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tv.nicdev.craftrelay.common.testing;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.state.NetworkPresenceStore;
import tv.nicdev.craftrelay.common.internal.state.PlayerMutationResult;
import tv.nicdev.craftrelay.common.internal.state.PlayerMutationStatus;
import tv.nicdev.craftrelay.common.internal.state.PlayerSessionKey;

/** Deterministic in-memory instance/player presence store for Common unit tests only. */
public final class TestNetworkPresenceStore implements NetworkPresenceStore {

    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final Map<UUID, PlayerLease> players = new HashMap<>();
    private final AtomicInteger heartbeatCalls = new AtomicInteger();

    private boolean connected;
    private boolean closed;

    public synchronized void seed(NetworkInstance instance, String token) {
        NetworkInstance value = Objects.requireNonNull(instance, "instance");
        leases.put(value.id(), new Lease(value, token));
    }

    public synchronized void seed(NetworkPlayer player, String token) {
        NetworkPlayer value = Objects.requireNonNull(player, "player");
        players.put(value.uniqueId(), new PlayerLease(value, token));
    }

    public int heartbeatCalls() {
        return heartbeatCalls.get();
    }

    public synchronized void forceRemove(String instanceId) {
        leases.remove(instanceId);
    }

    @Override
    public synchronized CompletableFuture<Void> connect() {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("store is closed"));
        }
        connected = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Boolean> claim(
            NetworkInstance instance, String leaseToken, Duration ttl) {
        requireConnected();
        Lease existing = leases.get(instance.id());
        if (existing != null && !existing.token().equals(leaseToken)) {
            return CompletableFuture.completedFuture(false);
        }
        leases.put(instance.id(), new Lease(instance, leaseToken));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletableFuture<Boolean> heartbeat(
            NetworkInstance instance, String leaseToken, Duration ttl) {
        requireConnected();
        heartbeatCalls.incrementAndGet();
        Lease existing = leases.get(instance.id());
        if (existing == null || !existing.token().equals(leaseToken)) {
            return CompletableFuture.completedFuture(false);
        }
        leases.put(instance.id(), new Lease(instance, leaseToken));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletableFuture<Boolean> release(
            String instanceId, String leaseToken) {
        requireConnected();
        Lease existing = leases.get(instanceId);
        if (existing == null || !existing.token().equals(leaseToken)) {
            return CompletableFuture.completedFuture(false);
        }
        leases.remove(instanceId);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Void> cleanupExpired(int batchSize) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<PlayerMutationResult> claim(
            NetworkPlayer player, String nodeLeaseToken, Duration ttl) {
        requireConnected();
        PlayerLease existing = players.get(player.uniqueId());
        if (existing != null
                && (!existing.player().sessionId().equals(player.sessionId())
                        || !existing.token().equals(nodeLeaseToken))) {
            return CompletableFuture.completedFuture(new PlayerMutationResult(
                    PlayerMutationStatus.CONFLICT,
                    Optional.empty(),
                    Optional.of(existing.player())));
        }
        players.put(player.uniqueId(), new PlayerLease(player, nodeLeaseToken));
        return CompletableFuture.completedFuture(new PlayerMutationResult(
                PlayerMutationStatus.APPLIED,
                Optional.empty(),
                Optional.of(player)));
    }

    @Override
    public synchronized CompletableFuture<PlayerMutationResult> updateServer(
            UUID playerId,
            UUID sessionId,
            String proxyId,
            String nodeLeaseToken,
            Optional<String> serverId,
            Duration ttl) {
        requireConnected();
        PlayerLease existing = players.get(playerId);
        if (!owns(existing, sessionId, proxyId, nodeLeaseToken)) {
            return CompletableFuture.completedFuture(lost());
        }
        NetworkPlayer previous = existing.player();
        NetworkPlayer updated = new NetworkPlayer(
                previous.uniqueId(),
                previous.username(),
                previous.proxyId(),
                serverId,
                previous.sessionId(),
                previous.connectedAt(),
                java.time.Instant.now());
        players.put(playerId, new PlayerLease(updated, nodeLeaseToken));
        return CompletableFuture.completedFuture(new PlayerMutationResult(
                PlayerMutationStatus.APPLIED,
                Optional.of(previous),
                Optional.of(updated)));
    }

    @Override
    public synchronized CompletableFuture<PlayerMutationResult> release(
            UUID playerId, UUID sessionId, String proxyId, String nodeLeaseToken) {
        requireConnected();
        PlayerLease existing = players.get(playerId);
        if (!owns(existing, sessionId, proxyId, nodeLeaseToken)) {
            return CompletableFuture.completedFuture(lost());
        }
        players.remove(playerId);
        return CompletableFuture.completedFuture(new PlayerMutationResult(
                PlayerMutationStatus.APPLIED,
                Optional.of(existing.player()),
                Optional.empty()));
    }

    @Override
    public synchronized CompletableFuture<Set<PlayerSessionKey>> refresh(
            String proxyId,
            String nodeLeaseToken,
            Collection<PlayerSessionKey> sessions,
            Duration ttl) {
        requireConnected();
        return CompletableFuture.completedFuture(sessions.stream()
                .filter(session ->
                        owns(players.get(session.playerId()), session.sessionId(), proxyId, nodeLeaseToken))
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override
    public CompletableFuture<Collection<NetworkPlayer>> releaseStale(
            String proxyId, String nodeLeaseToken, int batchSize) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public synchronized CompletableFuture<Collection<NetworkPlayer>> releaseOwned(
            String proxyId, String nodeLeaseToken, int batchSize) {
        List<NetworkPlayer> removed = players.values().stream()
                .filter(lease -> lease.player().proxyId().equals(proxyId)
                        && lease.token().equals(nodeLeaseToken))
                .limit(batchSize)
                .map(PlayerLease::player)
                .toList();
        removed.forEach(player -> players.remove(player.uniqueId()));
        return CompletableFuture.completedFuture(removed);
    }

    @Override
    public CompletableFuture<Void> cleanupExpiredPlayers(int batchSize) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
        requireConnected();
        return CompletableFuture.completedFuture(
                Optional.ofNullable(players.get(playerId)).map(PlayerLease::player));
    }

    @Override
    public synchronized CompletableFuture<? extends Collection<NetworkInstance>> instances() {
        requireConnected();
        return CompletableFuture.completedFuture(
                leases.values().stream().map(Lease::instance).toList());
    }

    @Override
    public synchronized CompletableFuture<Void> close() {
        closed = true;
        connected = false;
        return CompletableFuture.completedFuture(null);
    }

    private void requireConnected() {
        if (!connected || closed) {
            throw new IllegalStateException("store is not connected");
        }
    }

    private record Lease(NetworkInstance instance, String token) {

        private Lease {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(token, "token");
        }
    }

    private static boolean owns(
            PlayerLease lease, UUID sessionId, String proxyId, String token) {
        return lease != null
                && lease.player().sessionId().equals(sessionId)
                && lease.player().proxyId().equals(proxyId)
                && lease.token().equals(token);
    }

    private static PlayerMutationResult lost() {
        return new PlayerMutationResult(
                PlayerMutationStatus.OWNERSHIP_LOST,
                Optional.empty(),
                Optional.empty());
    }

    private record PlayerLease(NetworkPlayer player, String token) {

        private PlayerLease {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(token, "token");
        }
    }
}
