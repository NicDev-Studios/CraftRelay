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
package tv.nicdev.craftrelay.platform.velocity.internal.lifecycle;

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
import tv.nicdev.craftrelay.platform.velocity.CraftRelayReadyEvent;
import tv.nicdev.craftrelay.platform.velocity.internal.messaging.PlayerConnectRequestListener;
import tv.nicdev.craftrelay.platform.velocity.internal.player.LocalPlayerSessions;
import tv.nicdev.craftrelay.platform.velocity.internal.player.VelocityPlayerPresenceListener;
import tv.nicdev.craftrelay.transport.redis.RedisCraftRelayNodeFactory;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayConfigFiles;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayRedisConfig;

/** Owns Velocity node composition, API publication, and shutdown. */
public final class VelocityPluginLifecycle {

    private static final System.Logger LOGGER =
            System.getLogger(VelocityPluginLifecycle.class.getName());

    private final Object plugin;
    private final ProxyServer server;
    private final Path dataDirectory;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<CraftRelayApi> publicApi = new AtomicReference<>();
    private final AtomicReference<VelocityPlayerPresenceListener> presenceListener =
            new AtomicReference<>();

    private volatile CraftRelayNode node;
    private volatile Subscription connectSubscription;

    /**
     * Creates a Velocity lifecycle owner.
     *
     * @param plugin owning plugin
     * @param server active proxy
     * @param dataDirectory plugin data directory
     */
    public VelocityPluginLifecycle(Object plugin, ProxyServer server, Path dataDirectory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /**
     * Creates and starts the node.
     *
     * @return startup completion
     */
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> startup;
        try {
            CraftRelayRedisConfig settings = CraftRelayConfigFiles.loadOrCreate(dataDirectory);
            LocalPlayerSessions sessions = new LocalPlayerSessions();
            AtomicReference<VelocityPlayerPresenceListener> callbackTarget = presenceListener;
            node = RedisCraftRelayNodeFactory.create(
                    settings,
                    NetworkInstanceType.PROXY,
                    server::getPlayerCount,
                    session -> {
                        VelocityPlayerPresenceListener current = callbackTarget.get();
                        if (current != null) {
                            current.handleOwnershipLoss(session);
                        }
                    });
            VelocityPlayerPresenceListener listener = new VelocityPlayerPresenceListener(
                    plugin,
                    server,
                    node.playerPresence(),
                    sessions,
                    settings.loginUnavailableMessage(),
                    settings.duplicateSessionMessage());
            presenceListener.set(listener);
            server.getEventManager().register(plugin, listener);

            PlayerConnectRequestListener connectListener =
                    new PlayerConnectRequestListener(plugin, server, sessions);
            startup = node.start().thenCompose(
                    ignored -> publishApiReady(node, connectListener));
        } catch (Exception failure) {
            startup = CompletableFuture.failedFuture(failure);
        }

        startup.whenComplete((ignored, failure) -> {
            if (failure == null) {
                LOGGER.log(System.Logger.Level.INFO, "CraftRelay is available on Velocity");
            } else {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "CraftRelay failed to start",
                        AsyncFailures.unwrap(failure));
                closeAfterFailedStart();
            }
        });
        return startup;
    }

    /**
     * Stops accepting work and closes the node.
     *
     * @return shutdown future, or empty when already stopping
     */
    public Optional<CompletableFuture<Void>> stop() {
        if (!stopping.compareAndSet(false, true)) {
            return Optional.empty();
        }
        publicApi.set(null);
        closeSubscription();
        unregisterPresenceListener();

        CraftRelayNode current = node;
        return current == null ? Optional.empty() : Optional.of(current.close());
    }

    /**
     * Returns the currently published API.
     *
     * @return available API, or empty during startup and shutdown
     */
    public Optional<CraftRelayApi> api() {
        return Optional.ofNullable(publicApi.get());
    }

    private CompletableFuture<Void> publishApiReady(
            CraftRelayNode current, PlayerConnectRequestListener connectListener) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        schedule(() -> {
            if (stopping.get()) {
                completion.completeExceptionally(
                        new ApiUnavailableException("Velocity plugin is stopping"));
                return;
            }
            try {
                CraftRelayApi api = current.api();
                connectSubscription =
                        api.subscribe(PlayerConnectRequest.class, connectListener::handle);
                publicApi.set(api);
                server.getEventManager()
                        .fire(new CraftRelayReadyEvent(api))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                LOGGER.log(
                                        System.Logger.Level.WARNING,
                                        "A CraftRelayReadyEvent listener failed",
                                        AsyncFailures.unwrap(failure));
                            }
                            completion.complete(null);
                        });
            } catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
            }
        }, completion);
        return completion;
    }

    private void closeAfterFailedStart() {
        publicApi.set(null);
        closeSubscription();
        CraftRelayNode current = node;
        if (current != null) {
            current.close();
        }
        schedule(this::unregisterPresenceListener, null);
    }

    private void closeSubscription() {
        Subscription current = connectSubscription;
        connectSubscription = null;
        if (current != null) {
            current.close();
        }
    }

    private void unregisterPresenceListener() {
        VelocityPlayerPresenceListener current = presenceListener.getAndSet(null);
        if (current != null) {
            server.getEventManager().unregisterListener(plugin, current);
        }
    }

    private void schedule(Runnable action, CompletableFuture<Void> completion) {
        try {
            server.getScheduler().buildTask(plugin, action).schedule();
        } catch (RuntimeException failure) {
            if (completion != null) {
                completion.completeExceptionally(failure);
            } else {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Could not schedule Velocity lifecycle cleanup",
                        failure);
            }
        }
    }
}
