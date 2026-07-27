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
package tv.nicdev.craftrelay.platform.velocity.internal.player;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import tv.nicdev.craftrelay.api.model.NetworkPlayer;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.presence.PlayerPresence;
import tv.nicdev.craftrelay.common.internal.presence.PlayerSessionConflictException;
import tv.nicdev.craftrelay.common.internal.state.PlayerSessionKey;

/** Bridges Velocity player lifecycle events to distributed player presence. */
public final class VelocityPlayerPresenceListener {

    private static final System.Logger LOGGER =
            System.getLogger(VelocityPlayerPresenceListener.class.getName());

    private final Object plugin;
    private final ProxyServer server;
    private final PlayerPresence presence;
    private final LocalPlayerSessions sessions;
    private final String unavailableMessage;
    private final String duplicateMessage;

    /**
     * Creates a presence listener.
     *
     * @param plugin owning Velocity plugin
     * @param server active proxy
     * @param presence distributed presence facade
     * @param sessions shared local session index
     * @param unavailableMessage fail-closed login message
     * @param duplicateMessage duplicate-session login message
     */
    public VelocityPlayerPresenceListener(
            Object plugin,
            ProxyServer server,
            PlayerPresence presence,
            LocalPlayerSessions sessions,
            String unavailableMessage,
            String duplicateMessage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.presence = Objects.requireNonNull(presence, "presence");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.unavailableMessage = requireText(unavailableMessage, "unavailableMessage");
        this.duplicateMessage = requireText(duplicateMessage, "duplicateMessage");
    }

    /**
     * Claims a session before Velocity completes login.
     *
     * @param event login event
     * @return asynchronous continuation, or {@code null} if already denied
     */
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Objects.requireNonNull(event, "event");
        if (!event.getResult().isAllowed()) {
            return null;
        }

        Player player = event.getPlayer();
        UUID sessionId = UUID.randomUUID();
        CompletableFuture<NetworkPlayer> claim;
        try {
            claim = Objects.requireNonNull(
                    presence.connect(
                            player.getUniqueId(),
                            player.getUsername(),
                            sessionId,
                            Optional.empty()),
                    "presence.connect()");
        } catch (RuntimeException failure) {
            return EventTask.resumeWhenComplete(
                    runOnScheduler(() -> denyLogin(event, player, failure)));
        }

        CompletableFuture<Void> completion = claim
                .handle((snapshot, failure) -> runOnScheduler(() -> {
                    if (failure == null) {
                        sessions.set(player.getUniqueId(), sessionId);
                    } else {
                        denyLogin(event, player, failure);
                    }
                }))
                .thenCompose(future -> future);
        return EventTask.resumeWhenComplete(completion);
    }

    /**
     * Updates the player's authoritative backend server.
     *
     * @param event completed backend connection
     * @return asynchronous continuation, or {@code null} without a claimed session
     */
    @Subscribe
    public EventTask onServerPostConnect(ServerPostConnectEvent event) {
        Player player = Objects.requireNonNull(event, "event").getPlayer();
        Optional<UUID> session = sessions.find(player.getUniqueId());
        Optional<String> serverId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName());
        if (session.isEmpty() || serverId.isEmpty()) {
            return null;
        }
        return EventTask.resumeWhenComplete(presence
                .switchServer(player.getUniqueId(), session.orElseThrow(), serverId.orElseThrow())
                .handle((ignored, failure) -> {
                    logMutationFailure("server switch", player.getUniqueId(), failure);
                    return null;
                }));
    }

    /**
     * Releases the exact local session on disconnect.
     *
     * @param event disconnect event
     * @return asynchronous continuation, or {@code null} without a claimed session
     */
    @Subscribe
    public EventTask onDisconnect(DisconnectEvent event) {
        Player player = Objects.requireNonNull(event, "event").getPlayer();
        Optional<UUID> session = sessions.remove(player.getUniqueId());
        if (session.isEmpty()) {
            return null;
        }
        return EventTask.resumeWhenComplete(presence
                .disconnect(player.getUniqueId(), session.orElseThrow())
                .handle((ignored, failure) -> {
                    logMutationFailure("disconnect", player.getUniqueId(), failure);
                    return null;
                }));
    }

    /**
     * Disconnects a player only if the lost session is still locally current.
     *
     * @param session lost session ownership
     */
    public void handleOwnershipLoss(PlayerSessionKey session) {
        Objects.requireNonNull(session, "session");
        if (!sessions.remove(session.playerId(), session.sessionId())) {
            return;
        }
        server.getScheduler()
                .buildTask(plugin, () -> server.getPlayer(session.playerId())
                        .ifPresent(player -> player.disconnect(Component.text(unavailableMessage))))
                .schedule();
    }

    private CompletableFuture<Void> runOnScheduler(Runnable action) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            server.getScheduler()
                    .buildTask(plugin, () -> {
                        try {
                            action.run();
                            completion.complete(null);
                        } catch (RuntimeException failure) {
                            completion.completeExceptionally(failure);
                        }
                    })
                    .schedule();
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private static void logMutationFailure(
            String operation, UUID playerId, Throwable failure) {
        if (failure != null) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Player presence " + operation + " failed for " + playerId,
                    AsyncFailures.unwrap(failure));
        }
    }

    private void denyLogin(LoginEvent event, Player player, Throwable failure) {
        Throwable cause = AsyncFailures.unwrap(failure);
        String message = cause instanceof PlayerSessionConflictException
                ? duplicateMessage
                : unavailableMessage;
        event.setResult(ComponentResult.denied(Component.text(message)));
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Player presence claim failed for " + player.getUniqueId(),
                cause);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
