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
package tv.nicdev.craftrelay.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.CraftRelayState;
import tv.nicdev.craftrelay.api.NetworkMessage;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.message.GlobalBroadcastMessage;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.model.NetworkInstance;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.api.target.NetworkTarget;
import tv.nicdev.craftrelay.api.target.NetworkTargets;

class ExampleCommandServiceTest {

    @Test
    void formatsStateInstancesAndPlayers() {
        RecordingApi api = new RecordingApi();
        Instant now = Instant.now();
        api.instances = List.of(new NetworkInstance(
                "server-b", NetworkInstanceType.SERVER, Optional.of("game"), now, now, 3));
        UUID playerId = UUID.randomUUID();
        api.player = Optional.of(new NetworkPlayer(
                playerId,
                "Player",
                "proxy-a",
                Optional.of("server-b"),
                UUID.randomUUID(),
                now,
                now));
        ExampleCommandService commands = new ExampleCommandService(api);

        assertEquals(
                List.of("CraftRelay state: AVAILABLE"),
                commands.execute(new String[] {"state"}).join());
        assertTrue(commands.execute(new String[] {"instances"}).join()
                .get(1)
                .contains("server-b"));
        assertTrue(commands.execute(new String[] {"player", playerId.toString()}).join()
                .getFirst()
                .contains("Player"));
    }

    @Test
    void publishesBroadcastAndConnectToAllProxies() {
        RecordingApi api = new RecordingApi();
        ExampleCommandService commands = new ExampleCommandService(api);
        UUID playerId = UUID.randomUUID();

        assertEquals(
                List.of("Broadcast published."),
                commands.execute(new String[] {"broadcast", "Hello", "network"}).join());
        assertEquals(NetworkTargets.allProxies(), api.target);
        assertEquals(new GlobalBroadcastMessage("Hello network"), api.message);

        commands.execute(new String[] {"connect", playerId.toString(), "lobby"}).join();
        assertEquals(NetworkTargets.allProxies(), api.target);
        PlayerConnectRequest request = assertInstanceOf(PlayerConnectRequest.class, api.message);
        assertEquals(playerId, request.playerId());
        assertEquals("lobby", request.serverId());
    }

    @Test
    void reportsInvalidArgumentsAndExceptionalFutures() {
        RecordingApi api = new RecordingApi();
        ExampleCommandService commands = new ExampleCommandService(api);

        assertTrue(commands.execute(new String[] {"player", "invalid"}).join()
                .getFirst()
                .startsWith("Invalid player UUID"));
        assertEquals(5, commands.execute(new String[] {"unknown"}).join().size());

        api.instancesFailure = new IllegalStateException("redis unavailable");
        assertEquals(
                List.of("CraftRelay operation failed: redis unavailable"),
                commands.execute(new String[] {"instances"}).join());
    }

    @Test
    void suggestsSubcommandsWithoutPlatformSpecificLogic() {
        assertEquals(
                List.of("state", "instances", "player", "broadcast", "connect"),
                ExampleCommandService.suggestions(new String[0]));
        assertEquals(
                List.of("state"),
                ExampleCommandService.suggestions(new String[] {"ST"}));
        assertEquals(
                List.of(),
                ExampleCommandService.suggestions(new String[] {"state", ""}));
    }

    private static final class RecordingApi implements CraftRelayApi {

        private Collection<NetworkInstance> instances = List.of();
        private Optional<NetworkPlayer> player = Optional.empty();
        private RuntimeException instancesFailure;
        private NetworkTarget target;
        private NetworkMessage message;

        @Override
        public CompletableFuture<Void> publish(
                NetworkTarget publishedTarget, NetworkMessage publishedMessage) {
            target = publishedTarget;
            message = publishedMessage;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends NetworkMessage> Subscription subscribe(
                Class<M> messageType, Consumer<? super M> listener) {
            return Subscription.create(() -> {
                // Nothing to unregister in this recording API.
            });
        }

        @Override
        public <R extends NetworkMessage> CompletableFuture<R> request(
                NetworkTarget target,
                NetworkMessage request,
                Class<R> responseType,
                Duration timeout) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Collection<NetworkInstance>> instances() {
            return instancesFailure == null
                    ? CompletableFuture.completedFuture(instances)
                    : CompletableFuture.failedFuture(instancesFailure);
        }

        @Override
        public CompletableFuture<Optional<NetworkPlayer>> player(UUID playerId) {
            return CompletableFuture.completedFuture(player);
        }

        @Override
        public CraftRelayState state() {
            return CraftRelayState.AVAILABLE;
        }
    }
}
