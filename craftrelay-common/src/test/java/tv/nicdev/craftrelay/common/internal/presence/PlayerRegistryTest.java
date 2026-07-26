/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package tv.nicdev.craftrelay.common.internal.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.PlayerDisconnectedMessage;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.testing.TestMessagingRuntime;
import tv.nicdev.craftrelay.common.testing.TestNetworkPresenceStore;

class PlayerRegistryTest {

    private static final PlayerPresenceConfig CONFIG = new PlayerPresenceConfig(
            "test", Duration.ofHours(1), Duration.ofHours(2), 16);

    @Test
    void serializesConnectSwitchAndDisconnectAndTracksCount() {
        TestNetworkPresenceStore store = new TestNetworkPresenceStore();
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        PlayerRegistry registry = registry(store, runtime, "proxy-a", NodeLease.create());
        store.connect().join();
        registry.start().join();
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        CompletableFuture<NetworkPlayer> connect =
                registry.connect(playerId, "Player", sessionId, Optional.empty());
        CompletableFuture<NetworkPlayer> switched =
                registry.switchServer(playerId, sessionId, "lobby");
        CompletableFuture<Boolean> disconnected =
                registry.disconnect(playerId, sessionId);

        assertEquals(Optional.empty(), connect.join().serverId());
        assertEquals(Optional.of("lobby"), switched.join().serverId());
        assertEquals(true, disconnected.join());
        assertEquals(0, registry.onlinePlayerCount());
        assertEquals(Optional.empty(), registry.player(playerId).join());
        assertInstanceOf(
                PlayerDisconnectedMessage.class,
                runtime.lastPublished().message());
        registry.stop().join();
        store.close().join();
    }

    @Test
    void rejectsDuplicateSessionAndFencesOldOwnerUpdates() {
        TestNetworkPresenceStore store = new TestNetworkPresenceStore();
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        NodeLease firstLease = NodeLease.create();
        PlayerRegistry first = registry(store, runtime, "proxy-a", firstLease);
        PlayerRegistry contender = registry(store, runtime, "proxy-a", NodeLease.create());
        store.connect().join();
        first.start().join();
        contender.start().join();
        UUID playerId = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID();
        first.connect(playerId, "Player", firstSession, Optional.empty()).join();

        CompletionException conflict = assertThrows(
                CompletionException.class,
                () -> contender
                        .connect(playerId, "Player", UUID.randomUUID(), Optional.empty())
                        .join());
        assertInstanceOf(PlayerSessionConflictException.class, conflict.getCause());

        store.seed(
                new NetworkPlayer(
                        playerId,
                        "Player",
                        "proxy-a",
                        Optional.of("new-server"),
                        UUID.randomUUID(),
                        java.time.Instant.now(),
                        java.time.Instant.now()),
                "new-token");
        CompletionException fenced = assertThrows(
                CompletionException.class,
                () -> first.switchServer(playerId, firstSession, "old-server").join());
        assertInstanceOf(ApiUnavailableException.class, fenced.getCause());
        assertEquals(0, first.onlinePlayerCount());

        first.stop().join();
        contender.stop().join();
        store.close().join();
    }

    @Test
    void failedDisconnectStopsRefreshingTheDepartedLocalSession() {
        TestNetworkPresenceStore store = new TestNetworkPresenceStore();
        PlayerRegistry registry =
                registry(store, new TestMessagingRuntime(), "proxy-a", NodeLease.create());
        store.connect().join();
        registry.start().join();
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        registry.connect(playerId, "Player", sessionId, Optional.empty()).join();
        store.failNextPlayerRelease();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> registry.disconnect(playerId, sessionId).join());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, registry.onlinePlayerCount());
        registry.stop().join();
        store.close().join();
    }

    @Test
    void serverNodesRemainReadOnly() {
        TestNetworkPresenceStore store = new TestNetworkPresenceStore();
        TestMessagingRuntime runtime = new TestMessagingRuntime();
        PlayerRegistry registry = new PlayerRegistry(
                store,
                runtime,
                new LocalInstanceIdentity(
                        "server-a", NetworkInstanceType.SERVER, Optional.empty()),
                CONFIG,
                NodeLease.create(),
                ignored -> {});
        store.connect().join();
        registry.start().join();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> registry
                        .connect(
                                UUID.randomUUID(),
                                "Player",
                                UUID.randomUUID(),
                                Optional.empty())
                        .join());
        assertInstanceOf(ApiUnavailableException.class, failure.getCause());
        registry.stop().join();
        store.close().join();
    }

    private static PlayerRegistry registry(
            TestNetworkPresenceStore store,
            TestMessagingRuntime runtime,
            String proxyId,
            NodeLease lease) {
        return new PlayerRegistry(
                store,
                runtime,
                new LocalInstanceIdentity(
                        proxyId, NetworkInstanceType.PROXY, Optional.empty()),
                CONFIG,
                lease,
                ignored -> {});
    }
}
