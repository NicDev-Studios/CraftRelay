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

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresence;
import tv.nicdev.craftrelay.common.internal.presence.PlayerSessionConflictException;
import tv.nicdev.craftrelay.platform.velocity.internal.messaging.PlayerConnectRequestListener;
import tv.nicdev.craftrelay.platform.velocity.internal.player.LocalPlayerSessions;
import tv.nicdev.craftrelay.platform.velocity.internal.player.VelocityPlayerPresenceListener;

class VelocityPlayerAdaptersTest {

    /** Confirms disconnect releases the same session acquired during login. */
    @Test
    void loginAndDisconnectUseTheSameClaimedSession() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        UUID playerId = UUID.randomUUID();
        Player player = player(playerId, "Player", null);
        LocalPlayerSessions sessions = new LocalPlayerSessions();
        VelocityPlayerPresenceListener listener = new VelocityPlayerPresenceListener(
                new Object(),
                proxyServer(null, null),
                presence,
                sessions,
                "down",
                "duplicate");

        await(listener.onLogin(new LoginEvent(player, null)));
        UUID sessionId = presence.connectedSession;
        assertNotNull(sessionId);

        await(listener.onDisconnect(
                new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)));

        assertEquals(playerId, presence.disconnectedPlayer);
        assertEquals(sessionId, presence.disconnectedSession);
    }

    /** Confirms duplicate ownership denies login after returning to Velocity's scheduler. */
    @Test
    void duplicateSessionDeniesLoginAfterReturningToScheduler() throws Exception {
        RecordingPresence presence = new RecordingPresence();
        presence.connectFailure = new PlayerSessionConflictException("active duplicate");
        LoginEvent event = new LoginEvent(player(UUID.randomUUID(), "Player", null), null);
        VelocityPlayerPresenceListener listener = new VelocityPlayerPresenceListener(
                new Object(),
                proxyServer(null, null),
                presence,
                new LocalPlayerSessions(),
                "down",
                "duplicate");

        await(listener.onLogin(event));

        assertFalse(event.getResult().isAllowed());
    }

    /** Confirms Redis failures deny login without exposing the exception message. */
    @Test
    void redisFailureDeniesLoginWithoutLoggingFailureMessage() throws Exception {
        String syntheticSecret = "synthetic-secret-for-test";
        RecordingPresence presence = new RecordingPresence();
        presence.connectFailure = new ApiUnavailableException(syntheticSecret);
        AtomicReference<String> warning = new AtomicReference<>();
        Logger logger = dynamicProxy(Logger.class, (proxy, method, arguments) -> {
            if (method.getName().equals("warn")) {
                warning.set(Arrays.deepToString(arguments));
            }
            if (method.getReturnType() == String.class) {
                return "test";
            }
            if (method.getReturnType() == boolean.class) {
                return true;
            }
            return null;
        });
        LoginEvent event = new LoginEvent(player(UUID.randomUUID(), "Player", null), null);
        VelocityPlayerPresenceListener listener = new VelocityPlayerPresenceListener(
                new Object(),
                proxyServer(null, null),
                presence,
                new LocalPlayerSessions(),
                "down",
                "duplicate",
                logger);

        await(listener.onLogin(event));

        assertFalse(event.getResult().isAllowed());
        assertNotNull(warning.get());
        assertFalse(warning.get().contains(syntheticSecret));
    }

    /** Confirms connect requests run on the scheduler and use Velocity's async result. */
    @Test
    void connectRequestIsScheduledAndUsesVelocitysAsyncResult() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger connectionCalls = new AtomicInteger();
        RegisteredServer destination = registeredServer("lobby");
        Player player = player(
                playerId, "Player", connectionRequest(destination, connectionCalls));
        LocalPlayerSessions sessions = new LocalPlayerSessions();
        sessions.set(playerId, UUID.randomUUID());
        PlayerConnectRequestListener listener = new PlayerConnectRequestListener(
                new Object(), proxyServer(player, destination), sessions);

        listener.handle(new PlayerConnectRequest(playerId, "lobby"));

        assertEquals(1, connectionCalls.get());
    }

    /** Executes an event continuation and waits for its completion in a test.
     *
     * @param task event continuation, or {@code null} when the event was not deferred
     * @throws Exception if the continuation fails or exceeds the test timeout
     */
    private static void await(EventTask task) throws Exception {
        if (task == null) {
            return;
        }
        CompletableFuture<Void> completed = new CompletableFuture<>();
        task.execute(new Continuation() {
            /** Completes the waiting test future after successful continuation. */
            @Override
            public void resume() {
                completed.complete(null);
            }

            /** Propagates a continuation failure to the waiting test future. */
            @Override
            public void resumeWithException(Throwable exception) {
                completed.completeExceptionally(exception);
            }
        });
        completed.get(5, TimeUnit.SECONDS);
    }

    /** Creates a minimal player proxy backed by the values needed by these tests.
     *
     * @param playerId test player identity
     * @param username test player name
     * @param requestBuilder connection request behavior
     * @return proxy implementing the Velocity player interface
     */
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

    /** Creates a proxy server whose scheduler runs queued tasks immediately.
     *
     * @param player test player returned by lookups
     * @param server test destination returned by lookups
     * @return minimal Velocity proxy server
     */
    private static ProxyServer proxyServer(Player player, RegisteredServer server) {
        Scheduler scheduler = dynamicProxy(
                Scheduler.class,
                (proxy, method, arguments) -> {
                    if ("buildTask".equals(method.getName())) {
                        Runnable action = (Runnable) arguments[1];
                        return taskBuilder(action);
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        return dynamicProxy(ProxyServer.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getPlayer" -> Optional.ofNullable(player);
            case "getServer" -> Optional.ofNullable(server);
            case "getScheduler" -> scheduler;
            case "toString" -> "TestProxyServer";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    /** Creates a task builder that runs its action when scheduled.
     *
     * @param action test action to execute
     * @return minimal task builder proxy
     */
    private static Scheduler.TaskBuilder taskBuilder(Runnable action) {
        return dynamicProxy(
                Scheduler.TaskBuilder.class,
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "schedule" -> {
                        action.run();
                        yield null;
                    }
                    case "delay", "repeat", "clearDelay", "clearRepeat" -> proxy;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    /** Creates a registered-server proxy with the requested name.
     *
     * @param name test server name
     * @return minimal registered server
     */
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

    /** Creates a successful connection request and records each connect attempt.
     *
     * @param destination destination returned as the attempted connection
     * @param calls counter incremented when the request is executed
     * @return minimal connection request builder
     */
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

    /** Creates a small test proxy for a platform interface.
     *
     * @param <T> platform interface type
     * @param type interface class
     * @param handler method behavior for the proxy
     * @return proxy cast to the requested interface
     */
    private static <T> T dynamicProxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static final class RecordingPresence implements PlayerPresence {

        private RuntimeException connectFailure;
        private UUID connectedSession;
        private UUID disconnectedPlayer;
        private UUID disconnectedSession;

        /** Records a session claim or returns the configured failure. */
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

        /** Marks server switching unsupported in this focused test double. */
        @Override
        public CompletableFuture<NetworkPlayer> switchServer(
                UUID playerId, UUID sessionId, String serverId) {
            throw new UnsupportedOperationException();
        }

        /** Records a successful disconnect for assertion by the test. */
        @Override
        public CompletableFuture<Boolean> disconnect(UUID playerId, UUID sessionId) {
            disconnectedPlayer = playerId;
            disconnectedSession = sessionId;
            return CompletableFuture.completedFuture(true);
        }

        /** Reports whether the test double currently holds a claimed session. */
        @Override
        public int onlinePlayerCount() {
            return connectedSession == null ? 0 : 1;
        }
    }
}
