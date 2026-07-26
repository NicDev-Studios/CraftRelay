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

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.Subscription;
import tv.nicdev.craftrelay.api.exception.ApiUnavailableException;
import tv.nicdev.craftrelay.api.message.PlayerConnectRequest;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNode;
import tv.nicdev.craftrelay.transport.redis.RedisCraftRelayNodeFactory;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayConfigFiles;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayRedisConfig;

/** Velocity entry point for one CraftRelay proxy node. */
@Plugin(
        id = "craftrelay",
        name = "CraftRelay",
        version = CraftRelayBuildInfo.VERSION,
        description = "Redis-backed Minecraft network synchronization",
        authors = {"NicDev-Studios"})
public final class CraftRelayVelocityPlugin {

    private static final System.Logger LOGGER =
            System.getLogger(CraftRelayVelocityPlugin.class.getName());

    private final ProxyServer server;
    private final Path dataDirectory;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<CraftRelayApi> publicApi = new AtomicReference<>();
    private final AtomicReference<VelocityPlayerBridge> bridge = new AtomicReference<>();

    private volatile CraftRelayNode node;
    private volatile Subscription connectSubscription;

    /**
     * Creates the injected Velocity plugin instance.
     *
     * @param server active proxy
     * @param dataDirectory plugin-owned data directory
     */
    @Inject
    public CraftRelayVelocityPlugin(
            ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = Objects.requireNonNull(server, "server");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /**
     * Returns the API after the node has become available.
     *
     * @return current API, or empty during startup and shutdown
     */
    public Optional<CraftRelayApi> api() {
        return Optional.ofNullable(publicApi.get());
    }

    /**
     * Starts the Redis node without blocking a Velocity event thread.
     *
     * @param event proxy initialization event
     * @return asynchronous startup continuation
     */
    @Subscribe
    public EventTask onProxyInitialize(ProxyInitializeEvent event) {
        CompletableFuture<Void> startup;
        try {
            CraftRelayRedisConfig settings =
                    CraftRelayConfigFiles.loadOrCreate(dataDirectory);
            node = RedisCraftRelayNodeFactory.create(
                    settings,
                    NetworkInstanceType.PROXY,
                    server::getPlayerCount,
                    session -> {
                        VelocityPlayerBridge current = bridge.get();
                        if (current != null) {
                            current.handleOwnershipLoss(session);
                        }
                    });
            VelocityPlayerBridge playerBridge = new VelocityPlayerBridge(
                    this,
                    server,
                    node.playerPresence(),
                    settings.loginUnavailableMessage(),
                    settings.duplicateSessionMessage());
            bridge.set(playerBridge);
            server.getEventManager().register(this, playerBridge);

            startup = node.start().thenCompose(ignored -> publishApiReady(node));
        } catch (Exception failure) {
            startup = CompletableFuture.failedFuture(failure);
        }

        startup.whenComplete((ignored, failure) -> {
            if (failure == null) {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "CraftRelay is available on Velocity");
            } else {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "CraftRelay failed to start",
                        AsyncFailures.unwrap(failure));
                closeAfterFailedStart();
            }
        });
        return EventTask.resumeWhenComplete(startup);
    }

    /**
     * Stops the node through Velocity's asynchronous shutdown continuation.
     *
     * @param event proxy shutdown event
     * @return asynchronous shutdown continuation, or {@code null} if no node was created
     */
    @Subscribe
    public EventTask onProxyShutdown(ProxyShutdownEvent event) {
        stopping.set(true);
        publicApi.set(null);

        Subscription currentSubscription = connectSubscription;
        if (currentSubscription != null) {
            currentSubscription.close();
            connectSubscription = null;
        }
        VelocityPlayerBridge currentBridge = bridge.getAndSet(null);
        if (currentBridge != null) {
            server.getEventManager().unregisterListener(this, currentBridge);
        }

        CraftRelayNode current = node;
        return current == null
                ? null
                : EventTask.resumeWhenComplete(current.close());
    }

    private CompletableFuture<Void> publishApiReady(CraftRelayNode current) {
        if (stopping.get()) {
            return CompletableFuture.failedFuture(
                    new ApiUnavailableException("Velocity plugin is stopping"));
        }
        CraftRelayApi api = current.api();
        VelocityPlayerBridge playerBridge =
                Objects.requireNonNull(bridge.get(), "player bridge");
        connectSubscription =
                api.subscribe(PlayerConnectRequest.class, playerBridge::handleConnectRequest);
        publicApi.set(api);
        return server.getEventManager()
                .fire(new CraftRelayReadyEvent(api))
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        LOGGER.log(
                                System.Logger.Level.WARNING,
                                "A CraftRelayReadyEvent listener failed",
                                AsyncFailures.unwrap(failure));
                    }
                    return null;
                });
    }

    private void closeAfterFailedStart() {
        publicApi.set(null);
        Subscription currentSubscription = connectSubscription;
        if (currentSubscription != null) {
            currentSubscription.close();
            connectSubscription = null;
        }
        VelocityPlayerBridge currentBridge = bridge.getAndSet(null);
        if (currentBridge != null) {
            server.getEventManager().unregisterListener(this, currentBridge);
        }
        CraftRelayNode current = node;
        if (current != null) {
            current.close();
        }
    }
}
