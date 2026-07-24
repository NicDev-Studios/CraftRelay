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
package tv.nicdev.craftrelay.common.internal.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerLocationRequest;
import tv.nicdev.craftrelay.api.message.PlayerLocationResponse;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTargets;
import tv.nicdev.craftrelay.common.internal.runtime.LocalInstanceIdentity;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntimeConfig;
import tv.nicdev.craftrelay.common.internal.state.NetworkStateProvider;
import tv.nicdev.craftrelay.common.testing.TestNetworkTransport;

class CraftRelayNodeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void exposesLifecycleSafeApiStateSnapshotsAndSelfRequests()
            throws Exception {
        UUID playerId = UUID.randomUUID();
        NetworkPlayer player = player(playerId);
        List<NetworkInstance> mutableInstances =
                new ArrayList<>(List.of(instance("proxy-1")));
        TestStateProvider stateProvider =
                new TestStateProvider(mutableInstances, Optional.of(player));
        CraftRelayNode node =
                node(new TestNetworkTransport(), stateProvider);
        CraftRelayApi api = node.api();

        assertEquals(CraftRelayState.INITIALIZING, api.state());
        assertFutureFailure(
                ApiUnavailableException.class,
                api.publish(
                        NetworkTargets.allInstances(),
                        new GlobalBroadcastMessage("too early")));
        assertThrows(
                ApiUnavailableException.class,
                () ->
                        api.subscribe(
                                GlobalBroadcastMessage.class,
                                ignored -> {}));

        CompletableFuture<Void> firstStart = node.start();
        CompletableFuture<Void> secondStart = node.start();
        assertSame(firstStart, secondStart);
        firstStart.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(CraftRelayState.AVAILABLE, api.state());

        Collection<NetworkInstance> snapshot =
                api.instances().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        mutableInstances.clear();
        assertEquals(1, snapshot.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.clear());
        assertEquals(
                Optional.of(player),
                api.player(playerId)
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

        CompletableFuture<Boolean> callbackThread =
                api.request(
                                NetworkTargets.instance("node-a"),
                                new PlayerLocationRequest(playerId),
                                PlayerLocationResponse.class,
                                TIMEOUT)
                        .thenApply(
                                response ->
                                        response.player().equals(Optional.of(player))
                                                && Thread.currentThread().isVirtual());
        assertTrue(
                callbackThread.get(
                        TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

        CompletableFuture<Void> firstClose = node.close();
        CompletableFuture<Void> secondClose = node.close();
        assertSame(firstClose, secondClose);
        firstClose.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(CraftRelayState.STOPPED, api.state());
        assertFutureFailure(
                ApiUnavailableException.class,
                api.player(playerId));
    }

    @Test
    void failedStartCanBeRetriedAndShutdownFailsPendingRequests()
            throws Exception {
        TestNetworkTransport transport = new TestNetworkTransport();
        transport.failNextConnects(1);
        CraftRelayNode node = node(transport, TestStateProvider.empty());
        CraftRelayApi api = node.api();

        assertThrows(
                ExecutionException.class,
                () -> node.start().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(CraftRelayState.INITIALIZING, api.state());
        node.start().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        CompletableFuture<PlayerLocationResponse> pending =
                api.request(
                        NetworkTargets.instance("missing-node"),
                        new PlayerLocationRequest(UUID.randomUUID()),
                        PlayerLocationResponse.class,
                        TIMEOUT);
        node.close().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertFutureFailure(ApiUnavailableException.class, pending);
        assertEquals(2, transport.connectCalls());
    }

    private static CraftRelayNode node(
            TestNetworkTransport transport, NetworkStateProvider stateProvider) {
        return CraftRelayNodes.create(
                transport,
                new LocalInstanceIdentity(
                        "node-a",
                        NetworkInstanceType.PROXY,
                        Optional.of("eu")),
                MessagingRuntimeConfig.defaults(),
                stateProvider);
    }

    private static NetworkInstance instance(String id) {
        Instant now = Instant.now();
        return new NetworkInstance(
                id,
                NetworkInstanceType.PROXY,
                Optional.of("eu"),
                now,
                now,
                1);
    }

    private static NetworkPlayer player(UUID playerId) {
        Instant now = Instant.now();
        return new NetworkPlayer(
                playerId,
                "Player",
                "proxy-1",
                Optional.of("lobby"),
                UUID.randomUUID(),
                now,
                now);
    }

    private static void assertFutureFailure(
            Class<? extends Throwable> expected,
            CompletableFuture<?> future)
            throws Exception {
        ExecutionException failure =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                future.get(
                                        TIMEOUT.toMillis(),
                                        TimeUnit.MILLISECONDS));
        assertInstanceOf(expected, failure.getCause());
    }

    private record TestStateProvider(
            List<NetworkInstance> instanceValues,
            Optional<NetworkPlayer> playerValue)
            implements NetworkStateProvider {

        private TestStateProvider {
            instanceValues =
                    java.util.Objects.requireNonNull(
                            instanceValues, "instanceValues");
            playerValue =
                    java.util.Objects.requireNonNull(
                            playerValue, "playerValue");
        }

        private static TestStateProvider empty() {
            return new TestStateProvider(new ArrayList<>(), Optional.empty());
        }

        @Override
        public CompletableFuture<? extends Collection<NetworkInstance>> instances() {
            return CompletableFuture.completedFuture(instanceValues);
        }

        @Override
        public CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
            return CompletableFuture.completedFuture(
                    playerValue.filter(
                            value -> value.uniqueId().equals(playerId)));
        }
    }
}
