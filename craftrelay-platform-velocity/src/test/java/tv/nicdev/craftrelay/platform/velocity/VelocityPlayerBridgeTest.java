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
package tv.nicdev.craftrelay.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresence;
import tv.nicdev.craftrelay.common.internal.presence.PlayerSessionConflictException;

class VelocityPlayerBridgeTest {

    @Test
    void loginAndDisconnectUseTheSameClaimedSession() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        UUID playerId = UUID.randomUUID();
        Player player = player(playerId, "Player", null);
        VelocityPlayerBridge bridge =
                new VelocityPlayerBridge(new Object(), proxyServer(null, null), presence, "down", "duplicate");

        await(bridge.onLogin(new LoginEvent(player)));
        UUID sessionId = presence.connectedSession;
        assertNotNull(sessionId);

        await(bridge.onDisconnect(
                new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)));

        assertEquals(playerId, presence.disconnectedPlayer);
        assertEquals(sessionId, presence.disconnectedSession);
    }

    @Test
    void duplicateSessionDeniesLoginWithoutThrowingFromTheEvent() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        presence.connectFailure =
                new PlayerSessionConflictException("active duplicate");
        LoginEvent event = new LoginEvent(player(UUID.randomUUID(), "Player", null));
        VelocityPlayerBridge bridge =
                new VelocityPlayerBridge(new Object(), proxyServer(null, null), presence, "down", "duplicate");

        await(bridge.onLogin(event));

        assertFalse(event.getResult().isAllowed());
        assertTrue(event.getResult().getReasonComponent().isPresent());
    }

    @Test
    void connectRequestUsesVelocitysObservedAsyncResult() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        UUID playerId = UUID.randomUUID();
        AtomicInteger connectionCalls = new AtomicInteger();
        RegisteredServer destination = registeredServer("lobby");
        ConnectionRequestBuilder requestBuilder = connectionRequest(destination, connectionCalls);
        Player player = player(playerId, "Player", requestBuilder);
        VelocityPlayerBridge bridge = new VelocityPlayerBridge(
                new Object(),
                proxyServer(player, destination),
                presence,
                "down",
                "duplicate");
        await(bridge.onLogin(new LoginEvent(player)));

        bridge.handleConnectRequest(new PlayerConnectRequest(playerId, "lobby"));

        assertEquals(1, connectionCalls.get());
    }

    private static void await(EventTask task) throws Exception {
        if (task == null) {
            return;
        }
        CompletableFuture<Void> completed = new CompletableFuture<>();
        task.execute(new Continuation() {
            @Override
            public void resume() {
                completed.complete(null);
            }

            @Override
            public void resumeWithException(Throwable exception) {
                completed.completeExceptionally(exception);
            }
        });
        completed.get(5, TimeUnit.SECONDS);
    }

    private static Player player(
            UUID playerId, String username, ConnectionRequestBuilder requestBuilder) {
        return dynamicProxy(Player.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getUsername" -> username;
            case "createConnectionRequest" -> requestBuilder;
            case "toString" -> "TestPlayer[" + playerId + ']';
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    private static ProxyServer proxyServer(Player player, RegisteredServer server) {
        return dynamicProxy(ProxyServer.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getPlayer" -> Optional.ofNullable(player);
            case "getServer" -> Optional.ofNullable(server);
            case "toString" -> "TestProxyServer";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    private static RegisteredServer registeredServer(String name) {
        ServerInfo serverInfo =
                new ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25565));
        return dynamicProxy(
                RegisteredServer.class,
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getServerInfo" -> serverInfo;
                    case "toString" -> "TestRegisteredServer[" + name + ']';
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static ConnectionRequestBuilder connectionRequest(
            RegisteredServer destination, AtomicInteger calls) {
        ConnectionRequestBuilder.Result result = dynamicProxy(
                ConnectionRequestBuilder.Result.class,
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isSuccessful" -> true;
                    case "getAttemptedConnection" -> destination;
                    case "getReasonComponent" -> Optional.empty();
                    case "toString" -> "SuccessfulConnectionResult";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        return dynamicProxy(
                ConnectionRequestBuilder.class,
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "connect" -> {
                        calls.incrementAndGet();
                        yield CompletableFuture.completedFuture(result);
                    }
                    case "getServer" -> destination;
                    case "toString" -> "TestConnectionRequest";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static <T> T dynamicProxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static final class RecordingPresence implements PlayerPresence {

        private RuntimeException connectFailure;
        private UUID connectedSession;
        private UUID disconnectedPlayer;
        private UUID disconnectedSession;

        @Override
        public CompletableFuture<NetworkPlayer> connect(
                UUID playerId,
                String username,
                UUID sessionId,
                Optional<String> serverId) {
            if (connectFailure != null) {
                return CompletableFuture.failedFuture(connectFailure);
            }
            connectedSession = sessionId;
            Instant now = Instant.now();
            return CompletableFuture.completedFuture(new NetworkPlayer(
                    playerId,
                    username,
                    "proxy-test",
                    serverId,
                    sessionId,
                    now,
                    now));
        }

        @Override
        public CompletableFuture<NetworkPlayer> switchServer(
                UUID playerId, UUID sessionId, String serverId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Boolean> disconnect(UUID playerId, UUID sessionId) {
            disconnectedPlayer = playerId;
            disconnectedSession = sessionId;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public int onlinePlayerCount() {
            return connectedSession == null ? 0 : 1;
        }
    }
}
