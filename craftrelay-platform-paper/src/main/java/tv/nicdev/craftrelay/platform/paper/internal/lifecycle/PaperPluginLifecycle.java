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
package tv.nicdev.craftrelay.platform.paper.internal.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import tv.nicdev.craftrelay.api.CraftRelayApi;
import tv.nicdev.craftrelay.api.model.NetworkInstanceType;
import tv.nicdev.craftrelay.common.internal.CraftRelayStartupBanner;
import tv.nicdev.craftrelay.common.internal.concurrent.AsyncFailures;
import tv.nicdev.craftrelay.common.internal.node.CraftRelayNode;
import tv.nicdev.craftrelay.common.internal.observability.DiagnosticEvent;
import tv.nicdev.craftrelay.common.internal.presence.PlayerOwnershipListener;
import tv.nicdev.craftrelay.platform.paper.internal.presence.PaperPlayerCounter;
import tv.nicdev.craftrelay.platform.paper.internal.service.PaperApiService;
import tv.nicdev.craftrelay.transport.redis.RedisCraftRelayNodeFactory;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayConfigFiles;
import tv.nicdev.craftrelay.transport.redis.config.CraftRelayRedisConfig;

/** Coordinates Paper startup and bounded synchronous shutdown. */
public final class PaperPluginLifecycle {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private final JavaPlugin plugin;
    private final PaperApiService apiService;
    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile CraftRelayNode node;
    private volatile CraftRelayRedisConfig settings;

    /**
     * Creates a lifecycle coordinator.
     *
     * @param plugin owning plugin
     */
    public PaperPluginLifecycle(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        apiService = new PaperApiService(plugin);
    }

    /** Starts the node asynchronously. */
    public void start() {
        for (String bannerLine : CraftRelayStartupBanner.lines()) {
            plugin.getLogger().info(bannerLine);
        }
        PaperPlayerCounter playerCounter =
                new PaperPlayerCounter(plugin.getServer().getOnlinePlayers().size());
        plugin.getServer().getPluginManager().registerEvents(playerCounter, plugin);

        try {
            settings = CraftRelayConfigFiles.loadOrCreate(plugin.getDataFolder().toPath());
            node = RedisCraftRelayNodeFactory.create(
                    settings,
                    NetworkInstanceType.SERVER,
                    playerCounter,
                    PlayerOwnershipListener.NOOP,
                    this::reportDiagnostic);
        } catch (Exception failure) {
            plugin.getLogger().severe(
                    "Could not load CraftRelay configuration ("
                            + failure.getClass().getName()
                            + ')');
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }

        node.start().whenComplete((ignored, failure) -> completeStartOnServerThread(failure));
    }

    /**
     * Stops accepting work and waits only at Paper's synchronous disable boundary.
     */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        apiService.unregister();

        CraftRelayNode current = node;
        if (current == null) {
            return;
        }
        CompletableFuture<Void> shutdown = current.close();
        Duration timeout =
                settings == null ? DEFAULT_SHUTDOWN_TIMEOUT : settings.shutdownTimeout();
        try {
            shutdown.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "CraftRelay shutdown was interrupted", failure);
        } catch (ExecutionException failure) {
            plugin.getLogger().warning(
                    "CraftRelay shutdown failed ("
                            + AsyncFailures.unwrap(failure).getClass().getName()
                            + ')');
        } catch (TimeoutException failure) {
            plugin.getLogger().log(
                    Level.WARNING, "CraftRelay shutdown exceeded " + timeout, failure);
        }
    }

    /**
     * Returns the published API.
     *
     * @return available API, or empty during startup and shutdown
     */
    public Optional<CraftRelayApi> api() {
        return apiService.api();
    }

    private void completeStartOnServerThread(Throwable failure) {
        try {
            plugin.getServer()
                    .getScheduler()
                    .runTask(plugin, () -> completeStart(failure));
        } catch (RuntimeException schedulingFailure) {
            CraftRelayNode current = node;
            if (current != null) {
                current.close();
            }
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not finish CraftRelay startup on the server thread",
                    schedulingFailure);
        }
    }

    private void completeStart(Throwable failure) {
        CraftRelayNode current = node;
        if (stopping.get() || !plugin.isEnabled()) {
            if (current != null) {
                current.close();
            }
            return;
        }
        if (failure != null) {
            plugin.getLogger().severe(
                    "CraftRelay failed to start ("
                            + AsyncFailures.unwrap(failure).getClass().getName()
                            + ')');
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }

        apiService.register(Objects.requireNonNull(current, "node").api());
        plugin.getLogger().info("CraftRelay is available as instance " + settings.instanceId());
    }

    private void reportDiagnostic(DiagnosticEvent event) {
        Level level = switch (event.code().severity()) {
            case INFO -> Level.INFO;
            case WARNING -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };
        plugin.getLogger().log(level, event.logMessage());
    }
}
