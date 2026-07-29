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
package tv.nicdev.craftrelay.platform.velocity.internal.messaging;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.platform.velocity.internal.player.LocalPlayerSessions;

/** Executes built-in player connection requests on Velocity's scheduler. */
public final class PlayerConnectRequestListener {

    private final Object plugin;
    private final ProxyServer server;
    private final LocalPlayerSessions sessions;
    private final Logger logger;

    /**
     * Creates a request listener.
     *
     * @param plugin owning plugin
     * @param server active proxy
     * @param sessions shared local session index
     */
    public PlayerConnectRequestListener(
            Object plugin, ProxyServer server, LocalPlayerSessions sessions) {
        this(
                plugin,
                server,
                sessions,
                LoggerFactory.getLogger(PlayerConnectRequestListener.class));
    }

    /**
     * Creates a request listener using the platform logger.
     *
     * @param plugin owning plugin
     * @param server active proxy
     * @param sessions shared local session index
     * @param logger platform logger
     */
    public PlayerConnectRequestListener(
            Object plugin,
            ProxyServer server,
            LocalPlayerSessions sessions,
            Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Schedules handling so the messaging dispatcher never touches Velocity state.
     *
     * @param request connection request
     */
    public void handle(PlayerConnectRequest request) {
        Objects.requireNonNull(request, "request");
        server.getScheduler().buildTask(plugin, () -> execute(request)).schedule();
    }

    private void execute(PlayerConnectRequest request) {
        if (sessions.find(request.playerId()).isEmpty()) {
            return;
        }
        Optional<Player> player = server.getPlayer(request.playerId());
        Optional<RegisteredServer> destination = server.getServer(request.serverId())
                .filter(candidate -> candidate.getServerInfo().getName().equals(request.serverId()));
        if (player.isEmpty()) {
            return;
        }
        if (destination.isEmpty()) {
            logger.warn(
                    "Unknown Velocity server in PlayerConnectRequest: {}", request.serverId());
            return;
        }
        player.orElseThrow()
                .createConnectionRequest(destination.orElseThrow())
                .connect()
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.warn(
                                "Player connection request failed for {}",
                                request.playerId(),
                                AsyncFailures.unwrap(failure));
                    } else if (!result.isSuccessful()) {
                        logger.warn(
                                "Velocity rejected player connection request for {}: {}",
                                request.playerId(),
                                result.getStatus());
                    }
                });
    }
}
